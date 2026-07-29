"""Garbled text-layer fallback tests (M3-CONTRACTS.md §2.1 扫描页判定
extension): a PDF page whose embedded subset font lacks a usable ToUnicode
CMap extracts as wrong-codepoint glyph soup; such a page must fall back to
the scanned-page path (page render + OCR/VLM) instead of chunking/indexing
the garbage.
"""

import pymupdf

from app.parsers.pdf import _is_garbled_text
from tests.conftest import make_pdf_bytes, post_parse

# Representative of a broken ToUnicode extraction of a Chinese document:
# mostly Myanmar-block and box-drawing glyphs, with a few surviving ASCII
# digits/percent signs (standard-encoded in the original font).
_GARBLED_TEXT = "ဢၽာ ▓▓▓ ၥၦၧ 5551 ▓ ၬၭၮ 0.3% ▓▓ ၯၰၱၲၳ ၴၵၶၷ ▓▓ ၸၹၺ 365 ၻၼၽ ▓▓▓ ၾၿ"


def test_is_garbled_text_detects_wrong_codepoint_glyph_soup():
    assert _is_garbled_text(_GARBLED_TEXT) is True


def test_is_garbled_text_accepts_normal_text_and_edge_inputs():
    # Normal Chinese (with fullwidth/CJK punctuation) and English pages
    # must never be misclassified, nor must empty/whitespace-only input.
    assert _is_garbled_text("京东云工厂架构文档：容量 24 核，费率 0.3%，期限 365 天。") is False
    assert _is_garbled_text("Hello kb-rag PDF, this page has a normal text layer.") is False
    assert _is_garbled_text("") is False
    assert _is_garbled_text("   \n\t ") is False


def test_garbled_pdf_page_falls_back_to_page_render(client, monkeypatch):
    # A PDF with a genuinely broken ToUnicode CMap cannot be fabricated
    # via pymupdf's own writer (it always emits a valid CMap), so the
    # broken extraction result is simulated at the get_text() boundary.
    monkeypatch.setattr(pymupdf.Page, "get_text", lambda self, *args, **kwargs: _GARBLED_TEXT)

    response = post_parse(client, "garbled.pdf", make_pdf_bytes(), "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    page = data["pages"][0]
    assert page["scanned"] is True
    assert page["text"] == ""  # glyph soup never reaches chunking
    assert len(data["images"]) == 1
    assert data["images"][0]["kind"] == "page_render"
    assert any("garbled" in warning for warning in data["warnings"])
