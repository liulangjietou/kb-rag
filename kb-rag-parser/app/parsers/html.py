"""HTML parser for .html / .htm files (M12-CONTRACTS.md section 2).

Uses only the standard library's ``html.parser.HTMLParser``, following the
precedent set by app/chat/html_adapter.py: an event-based tokenizer has no
DTD/entity machinery, so the XXE guardrails in app/security.py (which
target ``xml.etree``/``lxml``) do not apply, and no new dependency (bs4,
markdownify) is introduced for what is a linear text extraction.

The parser performs no network I/O whatsoever: external images, scripts
and stylesheets referenced by the page are dropped, never fetched — the
outbound-request ban of the requirement doc applies to web pages exactly
as it does to files, and the SSRF surface of URL import lives entirely in
kb-rag-server's fetcher, not here.

Author: owlzhangfq@gmail.com
"""

import logging
from html.parser import HTMLParser

from app.encoding import decode_bytes
from app.errors import ErrorCode, ParseError
from app.models import PageContent, ParseData
from app.parsers.base import BaseParser

logger = logging.getLogger(__name__)

# Content inside these elements is invisible on the rendered page, so it is
# noise for retrieval (tracking snippets, CSS rules, JS fallbacks).
_SKIPPED_TAGS = frozenset({"script", "style", "noscript", "template"})

# Elements that end the current line of text. Inline tags (a, span, b, em...)
# are deliberately absent: their text flows into the surrounding block.
_BLOCK_TAGS = frozenset({
    "p", "div", "section", "article", "aside", "header", "footer", "main",
    "nav", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody",
    "tr", "blockquote", "pre", "figure", "figcaption", "br", "hr",
})

_HEADING_LEVELS = {"h1": 1, "h2": 2, "h3": 3, "h4": 4, "h5": 5, "h6": 6}


class _MarkdownExtractor(HTMLParser):
    """Event-based extractor turning an HTML byte stream into markdown lines.

    Heading tags map to markdown heading prefixes, block boundaries flush
    the inline buffer into a line, and everything below a skipped element
    is discarded. ``convert_charrefs=True`` (the default) resolves entities
    like ``&amp;`` before ``handle_data`` sees them.
    """

    def __init__(self) -> None:
        super().__init__()
        self._lines: list[str] = []
        self._buffer: list[str] = []
        self._skip_depth = 0
        self._in_title = False
        self._title_parts: list[str] = []
        self._heading_level = 0

    def handle_starttag(self, tag, attrs):
        if tag in _SKIPPED_TAGS:
            self._skip_depth += 1
            return
        if self._skip_depth > 0:
            return
        if tag == "title":
            self._in_title = True
            return
        if tag in _HEADING_LEVELS:
            self._flush()
            self._heading_level = _HEADING_LEVELS[tag]
            return
        if tag in _BLOCK_TAGS:
            self._flush()

    def handle_endtag(self, tag):
        if tag in _SKIPPED_TAGS:
            # max() guards against a stray closing tag in malformed markup.
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if self._skip_depth > 0:
            return
        if tag == "title":
            self._in_title = False
            return
        if tag in _HEADING_LEVELS:
            self._flush()
            self._heading_level = 0
            return
        if tag in _BLOCK_TAGS:
            self._flush()

    def handle_data(self, data):
        if self._skip_depth > 0:
            return
        if self._in_title:
            self._title_parts.append(data)
            return
        self._buffer.append(data)

    def result_markdown(self) -> str:
        """Assembles the final markdown after ``close()`` has been called."""
        self._flush()
        lines = list(self._lines)
        title = " ".join(" ".join(self._title_parts).split())
        if title:
            lines.insert(0, f"# {title}")
        # Joining non-empty lines with a blank line yields clean markdown
        # paragraphs and collapses whatever vertical whitespace the page had.
        return "\n\n".join(lines)

    def _flush(self) -> None:
        # split()/join collapses the runs of indentation whitespace HTML
        # sources are full of; an all-whitespace buffer produces no line.
        text = " ".join("".join(self._buffer).split())
        self._buffer.clear()
        if not text:
            return
        if self._heading_level > 0:
            self._lines.append(f"{'#' * self._heading_level} {text}")
        else:
            self._lines.append(text)


class HtmlParser(BaseParser):
    """Handles .html and .htm — one page, no images, markdown-ish output."""

    def parse(self, content: bytes, filename: str) -> ParseData:
        try:
            extractor = _MarkdownExtractor()
            extractor.feed(decode_bytes(content))
            extractor.close()
            markdown = extractor.result_markdown()
        except Exception as exc:
            logger.error(
                "html parse failed, errorCode=%s, filename=%s",
                ErrorCode.PARSE_FAILED,
                filename,
            )
            raise ParseError(f"failed to parse html file: {exc}") from exc

        return ParseData(
            markdown=markdown,
            pages=[PageContent(page_no=1, text=markdown)],
            images=[],
        )
