"""Plain-text parser for .txt and .md files.

No XML, no zip, no external resources are involved, so none of the
security guardrails in app/security.py apply beyond the upload size cap
already enforced by the API layer. Markdown files are passed through
as-is since they already are the target "markdown" representation; plain
text files are wrapped verbatim (no synthetic markdown syntax is invented).
"""

import logging

from app.encoding import decode_bytes
from app.errors import ErrorCode, ParseError
from app.models import PageContent, ParseData
from app.parsers.base import BaseParser

logger = logging.getLogger(__name__)


class TextParser(BaseParser):
    """Handles both .txt and .md — markdown needs no transformation."""

    def parse(self, content: bytes, filename: str) -> ParseData:
        try:
            text = decode_bytes(content)
        except Exception as exc:
            logger.error(
                "text parse failed, errorCode=%s, filename=%s",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to decode text file: {exc}") from exc

        return ParseData(markdown=text, pages=[PageContent(page_no=1, text=text)], images=[])
