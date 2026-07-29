"""Strategy registry mapping a file extension to its BaseParser instance.

Adding support for a new format is a two-line change: implement a
BaseParser subclass in its own module, then register it in _REGISTRY below.
No changes to app/main.py or any other parser are required.

Author: owlzhangfq@gmail.com
"""

import logging

from app.errors import ErrorCode, UnsupportedFormatError
from app.parsers.base import BaseParser
from app.parsers.docx import DocxParser
from app.parsers.excel import CsvParser, ExcelParser
from app.parsers.html import HtmlParser
from app.parsers.pdf import PdfParser
from app.parsers.text import TextParser

logger = logging.getLogger(__name__)

_pdf_parser = PdfParser()
_docx_parser = DocxParser()
_text_parser = TextParser()
_excel_parser = ExcelParser()
_csv_parser = CsvParser()
_html_parser = HtmlParser()

# file_ext (lowercase, no leading dot) -> parser instance.
_REGISTRY: dict[str, BaseParser] = {
    "pdf": _pdf_parser,
    "docx": _docx_parser,
    "txt": _text_parser,
    "md": _text_parser,
    "sql": _text_parser,
    "xlsx": _excel_parser,
    "csv": _csv_parser,
    "html": _html_parser,
    "htm": _html_parser,
}


def get_parser(file_ext: str) -> BaseParser:
    """Look up the registered parser for file_ext, or raise UnsupportedFormatError."""
    normalized = (file_ext or "").strip().lower().lstrip(".")
    parser = _REGISTRY.get(normalized)
    if parser is None:
        logger.error(
            "unsupported file_ext, errorCode=%s, fileExt=%s",
            ErrorCode.PARSE_FAILED,
            file_ext,
        )
        raise UnsupportedFormatError(
            f"unsupported file_ext '{file_ext}', supported: {sorted(_REGISTRY)}"
        )
    return parser
