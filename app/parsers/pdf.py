"""PDF parser backed by PyMuPDF (imported as pymupdf/fitz)."""

import logging

import pymupdf

from app.errors import ErrorCode, ParseError
from app.models import PageContent, ParseData
from app.parsers.base import BaseParser

logger = logging.getLogger(__name__)


class PdfParser(BaseParser):
    """Extracts per-page plain text from a PDF using PyMuPDF.

    Note: this covers text-layer extraction only. Scanned-page OCR and VLM
    image description are out of scope for M1 (see requirement doc §4.2
    "文档智能解析"); ``images`` is always returned empty, matching the M1
    contract response shape.
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
            pages: list[PageContent] = []
            markdown_parts: list[str] = []
            for page_index in range(document.page_count):
                page = document.load_page(page_index)
                text = page.get_text()
                page_no = page_index + 1
                pages.append(PageContent(page_no=page_no, text=text))
                markdown_parts.append(f"## Page {page_no}\n\n{text}")
            return ParseData(markdown="\n\n".join(markdown_parts), pages=pages, images=[])
        except Exception as exc:
            logger.error(
                "pdf parse failed, errorCode=%s, filename=%s, stage=extract",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to extract pdf text: {exc}") from exc
        finally:
            document.close()
