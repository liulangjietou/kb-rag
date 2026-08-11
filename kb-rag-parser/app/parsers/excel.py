"""Tabular parsers: .xlsx via openpyxl and .csv via the stdlib csv module.

Security note: .xlsx is a zip package containing XML parts, so it goes
through ``ensure_zip_is_safe`` first (requirement §4.2). openpyxl's own XML
reader disables entity resolution when lxml is installed and falls back to
defusedxml otherwise (audited in app/security.py's module docstring), so no
extra XXE patch is needed here. .csv is plain text — no zip, no XML.

Author: owlzhangfq@gmail.com
"""

import csv
import io
import logging
from typing import List, Optional, Sequence

import openpyxl

from app.encoding import decode_bytes
from app.errors import ErrorCode, ParseError
from app.models import PageContent, ParseData
from app.parsers.base import BaseParser
from app.security import ensure_zip_is_safe

logger = logging.getLogger(__name__)


def _cell_to_str(value: object) -> str:
    return "" if value is None else str(value)


def _row_to_plain_line(row: Sequence[object]) -> str:
    return "\t".join(_cell_to_str(v) for v in row)


def _rows_to_markdown_table(rows: List[Sequence[object]]) -> str:
    if not rows:
        return ""
    header, *body_rows = rows
    header_cells = [_cell_to_str(v) for v in header]
    lines = [
        "| " + " | ".join(header_cells) + " |",
        "| " + " | ".join(["---"] * len(header_cells)) + " |",
    ]
    for row in body_rows:
        lines.append("| " + " | ".join(_cell_to_str(v) for v in row) + " |")
    return "\n".join(lines)


class ExcelParser(BaseParser):
    """Extracts every worksheet of an .xlsx workbook, one page per sheet."""

    def parse(self, content: bytes, filename: str) -> ParseData:
        ensure_zip_is_safe(content)

        try:
            # read_only=True streams rows without loading the whole sheet
            # into styled objects; data_only=True returns cached formula
            # results instead of formula source strings.
            workbook = openpyxl.load_workbook(
                io.BytesIO(content), data_only=True, read_only=True
            )
        except Exception as exc:
            logger.error(
                "xlsx parse failed, errorCode=%s, filename=%s, stage=open",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to open xlsx: {exc}") from exc

        try:
            pages: List[PageContent] = []
            markdown_parts: List[str] = []
            for page_no, sheet in enumerate(workbook.worksheets, start=1):
                rows = [list(row) for row in sheet.iter_rows(values_only=True)]
                plain_text = "\n".join(_row_to_plain_line(row) for row in rows)
                sheet_markdown = f"## Sheet: {sheet.title}\n\n{_rows_to_markdown_table(rows)}"
                pages.append(PageContent(page_no=page_no, text=plain_text, markdown=sheet_markdown))
                markdown_parts.append(sheet_markdown)
            return ParseData(markdown="\n\n".join(markdown_parts), pages=pages, images=[])
        except Exception as exc:
            logger.error(
                "xlsx parse failed, errorCode=%s, filename=%s, stage=extract",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to extract xlsx content: {exc}") from exc
        finally:
            workbook.close()


class CsvParser(BaseParser):
    """Extracts a .csv file as a single page plus a markdown table."""

    def parse(self, content: bytes, filename: str) -> ParseData:
        try:
            text = decode_bytes(content)
            dialect = self._sniff_dialect(text)
            rows = list(csv.reader(io.StringIO(text), dialect=dialect))
        except Exception as exc:
            logger.error(
                "csv parse failed, errorCode=%s, filename=%s",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to parse csv: {exc}") from exc

        plain_text = "\n".join(_row_to_plain_line(row) for row in rows)
        markdown = _rows_to_markdown_table(rows)
        return ParseData(
            markdown=markdown,
            pages=[PageContent(page_no=1, text=plain_text, markdown=markdown)],
            images=[],
        )

    @staticmethod
    def _sniff_dialect(text: str) -> Optional[type]:
        """Best-effort delimiter detection; defaults to comma on failure."""
        sample = text[:4096]
        try:
            return csv.Sniffer().sniff(sample, delimiters=",;\t")
        except csv.Error:
            return csv.excel
