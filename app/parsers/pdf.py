"""PDF parser backed by PyMuPDF (imported as pymupdf/fitz).

Author: owlzhangfq@gmail.com
"""

import logging
from typing import List, Optional, Tuple

import pymupdf

from app import config
from app.config import SCANNED_PAGE_RENDER_DPI, SCANNED_PAGE_TEXT_THRESHOLD
from app.errors import ErrorCode, ParseError
from app.models import PageContent, ParseData
from app.ocr.engine import get_ocr_engine
from app.parsers.base import BaseParser
from app.parsers.images import KIND_EMBEDDED, KIND_PAGE_RENDER, ImageAssetCollector, guess_media_type, image_placeholder

logger = logging.getLogger(__name__)

# PNG is what pymupdf's Pixmap.tobytes() is asked to produce for scanned
# page renders; kept as a constant to avoid repeating the literal.
_PAGE_RENDER_MEDIA_TYPE = "image/png"


class PdfParser(BaseParser):
    """Extracts per-page plain text, embedded images, and scanned-page
    renders from a PDF using PyMuPDF.

    Text-layer extraction is unchanged from M1. M3 (see M3-CONTRACTS.md
    §2.1) adds two things:
      - embedded raster images on each page (kind="embedded")
      - for a page with no usable text layer ("scanned"), a 150dpi PNG
        render of the whole page (kind="page_render") instead, so
        kb-rag-server's VLM can OCR it -- this parser does not run OCR
        itself by default (OCR_ENGINE=none, M3-CONTRACTS.md §0: OCR is a
        VLM responsibility on the server side)
    A scanned page's embedded images are not separately extracted: the
    whole page is already captured as one page_render image, so extracting
    its raw embedded images too would just double-count the same content.

    M8 (M8-CONTRACTS.md §0.4) adds an optional local OCR fallback: when
    OCR_ENGINE=paddle is configured (and the optional paddleocr dependency
    is installed), the same page_render PNG is also run through PaddleOCR;
    a successful result backfills this page's PageContent.text (and the
    corresponding markdown) and sets ocr_source="paddle" so kb-rag-server
    skips its own VLM call for that page. A timeout/failure/OCR_ENGINE=none
    leaves the page exactly as before (empty text, ocr_source=None) -- this
    is always a per-page degrade, never a whole-document failure.
    """

    def parse(self, content: bytes, filename: str) -> ParseData:
        try:
            # filetype="pdf" avoids MuPDF trying to sniff/guess the format.
            document = pymupdf.open(stream=content, filetype="pdf")
        except Exception as exc:  # pymupdf raises its own error types
            logger.error(
                "pdf parse failed, errorCode=%s, filename=%s, stage=open",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to open pdf: {exc}") from exc

        try:
            collector = ImageAssetCollector()
            pages: List[PageContent] = []
            markdown_parts: List[str] = []
            for page_index in range(document.page_count):
                page = document.load_page(page_index)
                text = page.get_text()
                page_no = page_index + 1
                scanned = len(text.strip()) < SCANNED_PAGE_TEXT_THRESHOLD
                ocr_source: Optional[str] = None

                if scanned:
                    placeholder_lines, ocr_text = self._render_and_ocr_scanned_page(page, page_no, collector)
                    if ocr_text:
                        text = ocr_text
                        ocr_source = config.OCR_SOURCE_PADDLE
                else:
                    placeholder_lines = self._extract_embedded_images(document, page, page_no, collector)

                pages.append(PageContent(page_no=page_no, text=text, scanned=scanned, ocr_source=ocr_source))
                page_markdown = f"## Page {page_no}\n\n{text}"
                if placeholder_lines:
                    page_markdown += "\n\n" + "\n".join(placeholder_lines)
                markdown_parts.append(page_markdown)

            return ParseData(
                markdown="\n\n".join(markdown_parts),
                pages=pages,
                images=collector.images,
                warnings=collector.warnings,
            )
        except Exception as exc:
            logger.error(
                "pdf parse failed, errorCode=%s, filename=%s, stage=extract",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to extract pdf text: {exc}") from exc
        finally:
            document.close()

    @staticmethod
    def _render_and_ocr_scanned_page(
        page, page_no: int, collector: ImageAssetCollector
    ) -> Tuple[List[str], Optional[str]]:
        """Render a text-less page to PNG (so kb-rag-server's VLM can OCR
        it, same as pre-M8), and additionally attempt this service's own
        PaddleOCR fallback on that same PNG (M8-CONTRACTS.md §0.4) -- a
        no-op returning None when OCR_ENGINE=none (the default)."""
        pixmap = page.get_pixmap(dpi=SCANNED_PAGE_RENDER_DPI)
        png_bytes = pixmap.tobytes("png")
        image_id = collector.try_add(
            page_no, kind=KIND_PAGE_RENDER, media_type=_PAGE_RENDER_MEDIA_TYPE, raw_bytes=png_bytes
        )
        placeholder_lines = [image_placeholder(image_id)] if image_id else []
        ocr_text = get_ocr_engine().recognize(png_bytes, page_no)
        return placeholder_lines, ocr_text

    @staticmethod
    def _extract_embedded_images(document, page, page_no: int, collector: ImageAssetCollector) -> List[str]:
        """Pull every raster image embedded on a (non-scanned) page."""
        placeholder_lines: List[str] = []
        for image_info in page.get_images(full=True):
            xref = image_info[0]
            try:
                extracted = document.extract_image(xref)
            except Exception:
                # A handful of malformed/unsupported xrefs shouldn't fail
                # the whole document; just skip that one image.
                logger.info(
                    "embedded image extraction skipped, pageNo=%d, xref=%d, reason=extract_image_failed",
                    page_no,
                    xref,
                )
                continue
            media_type = guess_media_type(extracted.get("ext", ""))
            image_id = collector.try_add(
                page_no, kind=KIND_EMBEDDED, media_type=media_type, raw_bytes=extracted["image"]
            )
            if image_id:
                placeholder_lines.append(image_placeholder(image_id))
        return placeholder_lines
