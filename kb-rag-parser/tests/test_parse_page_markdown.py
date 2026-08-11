"""Per-page markdown contract (M14-CONTRACTS.md §F3).

kb-rag-server's page-splitting strategy used to cut pages[].text, the plain
text as extracted. That text carries neither the page heading nor the
[[IMAGE:id]] placeholder lines, so a page chunk could never be linked to an
image, and the whole cleaning stage -- header/footer removal, watermarks,
regex replacements, masking -- had been applied to the merged markdown only,
never to what that strategy actually indexed.

pages[].markdown is this page's slice of the merged markdown, which is what
lets the server clean page by page and reassemble losslessly. The invariant
these tests protect: joining every pages[].markdown with a blank line
reproduces data["markdown"] exactly.
"""

import re

import pymupdf

from tests.conftest import (
    make_docx_bytes,
    make_pdf_with_embedded_image_bytes,
    make_xlsx_bytes,
    post_parse,
)

_PLACEHOLDER_PATTERN = re.compile(r"\[\[IMAGE:([^\]]+)\]\]")

_PAGE_SEPARATOR = "\n\n"


def _make_multi_page_pdf_bytes(pages: int = 3) -> bytes:
    """A PDF whose pages carry distinct text, so a boundary mix-up is visible."""
    document = pymupdf.open()
    for page_no in range(1, pages + 1):
        page = document.new_page()
        page.insert_text((72, 72), f"Page {page_no} body text, long enough to have a real text layer.")
    data = document.tobytes()
    document.close()
    return data


def _assert_pages_reassemble(data: dict) -> None:
    """The invariant kb-rag-server's PagedContentAssembler depends on."""
    assembled = _PAGE_SEPARATOR.join(page["markdown"] for page in data["pages"])
    assert assembled == data["markdown"]


def test_pdf_pages_carry_their_own_markdown_slice(client):
    response = post_parse(client, "multi.pdf", _make_multi_page_pdf_bytes(3), "pdf")

    data = response.json()["data"]
    assert len(data["pages"]) == 3
    for page_no, page in enumerate(data["pages"], start=1):
        assert page["markdown"].startswith(f"## Page {page_no}")
        assert f"Page {page_no} body text" in page["markdown"]
        # The plain text stays what it was: the header/footer detection compares
        # pages against each other and must keep seeing the extracted text.
        assert "## Page" not in page["text"]
    _assert_pages_reassemble(data)


def test_pdf_page_markdown_carries_the_image_placeholder(client):
    # The reason the field exists: on the old route the placeholder lived only
    # in the merged markdown, so every page chunk indexed without its image.
    response = post_parse(client, "with-image.pdf", make_pdf_with_embedded_image_bytes(), "pdf")

    data = response.json()["data"]
    page_markdown = data["pages"][0]["markdown"]
    placeholder_ids = _PLACEHOLDER_PATTERN.findall(page_markdown)
    assert placeholder_ids == [image["image_id"] for image in data["images"]]
    assert not _PLACEHOLDER_PATTERN.findall(data["pages"][0]["text"])
    _assert_pages_reassemble(data)


def test_txt_single_page_markdown_is_the_whole_document(client):
    response = post_parse(client, "sample.txt", "Hello kb-rag TXT\nsecond line".encode("utf-8"), "txt")

    data = response.json()["data"]
    assert data["pages"][0]["markdown"] == data["markdown"]
    _assert_pages_reassemble(data)


def test_html_single_page_markdown_is_the_whole_document(client):
    content = "<html><body><h1>Title</h1><p>Body text</p></body></html>".encode("utf-8")

    response = post_parse(client, "sample.html", content, "html")

    data = response.json()["data"]
    assert data["pages"][0]["markdown"] == data["markdown"]
    _assert_pages_reassemble(data)


def test_docx_single_page_markdown_keeps_what_the_plain_text_loses(client):
    response = post_parse(client, "sample.docx", make_docx_bytes(), "docx")

    data = response.json()["data"]
    page = data["pages"][0]
    assert page["markdown"] == data["markdown"]
    # The plain text of a docx page is not its markdown: headings and tables
    # survive only in the latter, which is precisely why the page strategy has
    # to cut the markdown and not the text.
    assert page["markdown"] != page["text"]
    _assert_pages_reassemble(data)


def test_excel_sheets_each_carry_their_own_markdown(client):
    response = post_parse(client, "sample.xlsx", make_xlsx_bytes(), "xlsx")

    data = response.json()["data"]
    for page in data["pages"]:
        assert page["markdown"].startswith("## Sheet:")
    _assert_pages_reassemble(data)
