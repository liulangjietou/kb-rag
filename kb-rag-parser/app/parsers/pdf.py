"""PDF parser backed by PyMuPDF (imported as pymupdf/fitz).

Author: owlzhangfq@gmail.com
"""

import logging
from typing import List, Optional, Set, Tuple

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

# Unicode ranges whose characters count as "recognizable" when deciding
# whether an extracted text layer is garbled (see _is_garbled_text).
# Covers ASCII, general punctuation, CJK punctuation, kana, CJK
# ideographs (base + extension A), and half/fullwidth forms.
_RECOGNIZABLE_CHAR_RANGES: Tuple[Tuple[int, int], ...] = (
    (0x0020, 0x007E),  # ASCII printable
    (0x2000, 0x206F),  # general punctuation (dashes, quotes, ellipsis)
    (0x3000, 0x303F),  # CJK symbols and punctuation
    (0x3040, 0x30FF),  # hiragana + katakana
    (0x3400, 0x4DBF),  # CJK unified ideographs extension A
    (0x4E00, 0x9FFF),  # CJK unified ideographs
    (0xFF00, 0xFFEF),  # halfwidth/fullwidth forms
)


def _is_garbled_text(text: str) -> bool:
    """Heuristic for a broken text layer: a PDF whose embedded subset font
    lacks a usable ToUnicode CMap extracts as wrong-codepoint "glyph soup"
    (e.g. CJK content surfacing as Myanmar/box-drawing glyphs while ASCII
    digits survive). Such a page has plenty of characters, so the
    scanned-page length threshold never catches it; instead, the page is
    declared garbled when the share of characters inside recognizable
    Unicode ranges falls below GARBLED_PAGE_VALID_CHAR_RATIO_PCT."""
    meaningful = [char for char in text if not char.isspace()]
    if not meaningful:
        return False
    recognizable = sum(
        1 for char in meaningful if any(low <= ord(char) <= high for low, high in _RECOGNIZABLE_CHAR_RANGES)
    )
    # Integer comparison of recognizable/len(meaningful) < pct/100.
    return recognizable * 100 < config.GARBLED_PAGE_VALID_CHAR_RATIO_PCT * len(meaningful)


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

    An embedded image is reported once per distinct xref rather than once
    per placement (see _extract_embedded_images): a header logo is a single
    PDF object drawn on every page, and reporting it per page would cost
    kb-rag-server one vision call per page for one picture.

    A page whose text layer extracts as garbled glyph soup (broken/missing
    ToUnicode CMap, see _is_garbled_text) is downgraded to the same
    scanned-page path: its unusable text is dropped, the page is rendered,
    and a warning is recorded.

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
            page_warnings: List[str] = []
            # Document-wide, so an image drawn on many pages is reported once.
            seen_xrefs: Set[int] = set()
            for page_index in range(document.page_count):
                page = document.load_page(page_index)
                text = page.get_text()
                page_no = page_index + 1
                if len(text.strip()) >= SCANNED_PAGE_TEXT_THRESHOLD and _is_garbled_text(text):
                    # Wrong-codepoint glyph soup must never reach chunking/
                    # indexing; blank it so this page is handled exactly
                    # like a scanned one below (page_render + OCR/VLM
                    # becomes its only text source).
                    logger.info(
                        "pdf page text garbled, falling back to page render, pageNo=%d, filename=%s",
                        page_no,
                        filename,
                    )
                    page_warnings.append(
                        f"page {page_no} text layer is garbled (likely a missing/broken ToUnicode CMap in an "
                        "embedded font); fell back to page render"
                    )
                    text = ""
                scanned = len(text.strip()) < SCANNED_PAGE_TEXT_THRESHOLD
                ocr_source: Optional[str] = None

                if scanned:
                    placeholder_lines, ocr_text = self._render_and_ocr_scanned_page(page, page_no, collector)
                    if ocr_text:
                        text = ocr_text
                        ocr_source = config.OCR_SOURCE_PADDLE
                else:
                    placeholder_lines = self._extract_embedded_images(
                        document, page, page_no, collector, seen_xrefs
                    )

                page_markdown = f"## Page {page_no}\n\n{text}"
                if placeholder_lines:
                    page_markdown += "\n\n" + "\n".join(placeholder_lines)
                markdown_parts.append(page_markdown)
                pages.append(
                    PageContent(
                        page_no=page_no,
                        text=text,
                        markdown=page_markdown,
                        scanned=scanned,
                        ocr_source=ocr_source,
                    )
                )

            return ParseData(
                markdown="\n\n".join(markdown_parts),
                pages=pages,
                images=collector.images,
                warnings=collector.warnings + page_warnings,
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
        no-op returning None when OCR_ENGINE=none (the default).

        The render is skipped outright once the document image cap is
        reached and no local OCR engine is configured, because then nobody
        would read the PNG: the collector would discard it and no text
        would come out of it. With OCR_ENGINE=paddle the page is still
        rendered past the cap -- the cap bounds the images the response
        carries, not this service's ability to read a page.
        """
        wants_asset = collector.has_capacity(page_no, KIND_PAGE_RENDER)
        ocr_enabled = config.OCR_ENGINE != config.OCR_ENGINE_NONE
        if not wants_asset and not ocr_enabled:
            return [], None
        pixmap = page.get_pixmap(dpi=SCANNED_PAGE_RENDER_DPI)
        png_bytes = pixmap.tobytes("png")
        image_id = (
            collector.try_add(
                page_no, kind=KIND_PAGE_RENDER, media_type=_PAGE_RENDER_MEDIA_TYPE, raw_bytes=png_bytes
            )
            if wants_asset
            else None
        )
        placeholder_lines = [image_placeholder(image_id)] if image_id else []
        ocr_text = get_ocr_engine().recognize(png_bytes, page_no)
        return placeholder_lines, ocr_text

    @staticmethod
    def _extract_embedded_images(
        document, page, page_no: int, collector: ImageAssetCollector, seen_xrefs: Set[int]
    ) -> List[str]:
        """Pull the raster images embedded on a (non-scanned) page that no
        earlier page has already reported.

        An xref identifies one image object within the file, and a page
        header logo is a single such object drawn on every page. Reporting
        it per placement instead of per object made a 250-page document
        report the same two rasters ~250 times: each copy base64'd into the
        response, then described by kb-rag-server's vision model again --
        hundreds of serial model calls for two pictures, and hundreds of
        "image count limit reached" warnings once the cap cut them off.

        So one distinct image yields one asset, whose placeholder marks its
        first occurrence. That also keeps the placeholder ids in markdown
        unique, which is what kb-rag-server's substitution relies on: were
        one id to appear on 250 pages, its vision description would be
        spliced into all 250 and pollute every chunk.
        """
        placeholder_lines: List[str] = []
        for image_info in page.get_images(full=True):
            xref = image_info[0]
            if xref in seen_xrefs:
                continue
            # Marked before the attempt, so a malformed object is tried once
            # for the document rather than once per page that draws it.
            seen_xrefs.add(xref)
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
