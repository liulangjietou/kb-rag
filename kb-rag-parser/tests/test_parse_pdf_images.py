"""M3 pdf multimodal tests (M3-CONTRACTS.md §2.1): scanned-page detection
and rendering, embedded image extraction, and placeholder/images[]
consistency.
"""

import re

import pymupdf

from tests.conftest import (
    make_pdf_with_embedded_image_bytes,
    make_pdf_with_repeated_image_bytes,
    make_scanned_pdf_bytes,
    post_parse,
)

_PLACEHOLDER_PATTERN = re.compile(r"\[\[IMAGE:([^\]]+)\]\]")


def _assert_placeholders_match_images(data: dict) -> None:
    """Every [[IMAGE:id]] token in markdown has exactly one images[] entry
    with that id, and vice versa -- the core placeholder/images invariant
    kb-rag-server relies on to replace tokens in place."""
    placeholder_ids = _PLACEHOLDER_PATTERN.findall(data["markdown"])
    image_ids = [image["image_id"] for image in data["images"]]
    assert sorted(placeholder_ids) == sorted(image_ids)
    assert len(placeholder_ids) == len(set(placeholder_ids)), "placeholder ids must be unique"


def test_parse_pdf_scanned_page_is_rendered_to_png(client):
    content = make_scanned_pdf_bytes()

    response = post_parse(client, "scanned.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["pages"]) == 1
    assert data["pages"][0]["scanned"] is True

    assert len(data["images"]) == 1
    image = data["images"][0]
    assert image["kind"] == "page_render"
    assert image["media_type"] == "image/png"
    assert image["page_no"] == 1
    assert image["content_base64"]

    _assert_placeholders_match_images(data)
    assert data["warnings"] == []


def test_parse_pdf_embedded_image_is_extracted(client):
    content = make_pdf_with_embedded_image_bytes()

    response = post_parse(client, "with_image.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    # Enough text on the page that it is NOT treated as scanned.
    assert data["pages"][0]["scanned"] is False

    assert len(data["images"]) == 1
    image = data["images"][0]
    assert image["kind"] == "embedded"
    assert image["media_type"] in ("image/png", "image/jpeg")
    assert image["page_no"] == 1

    _assert_placeholders_match_images(data)
    assert data["warnings"] == []


def test_parse_pdf_image_count_cap_skips_extra_images_with_warning(client, monkeypatch):
    from app import config

    monkeypatch.setattr(config, "MAX_IMAGES_PER_DOC", 0)

    content = make_pdf_with_embedded_image_bytes()
    response = post_parse(client, "with_image.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    # The image was skipped (cap of 0), but the rest of the document still
    # parses successfully -- contract §2.1 "超限跳过并写 warnings，不失败整篇".
    assert data["images"] == []
    assert "[[IMAGE:" not in data["markdown"]
    assert len(data["warnings"]) == 1
    assert "limit" in data["warnings"][0]


def test_parse_pdf_image_byte_cap_skips_oversized_image_with_warning(client, monkeypatch):
    from app import config

    monkeypatch.setattr(config, "MAX_IMAGE_BYTES", 1)

    content = make_pdf_with_embedded_image_bytes()
    response = post_parse(client, "with_image.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert data["images"] == []
    assert "[[IMAGE:" not in data["markdown"]
    assert len(data["warnings"]) == 1
    assert "bytes" in data["warnings"][0]


def test_parse_pdf_reports_a_repeated_image_once(client):
    """A header logo drawn on every page is one image object, so it must
    yield one images[] entry and one placeholder -- not one per page, which
    would cost kb-rag-server a vision call per page for a single picture."""
    content = make_pdf_with_repeated_image_bytes(pages=4)

    response = post_parse(client, "repeated_logo.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["pages"]) == 4
    assert len(data["images"]) == 1, "the same raster on 4 pages is still one image"
    assert data["images"][0]["kind"] == "embedded"
    # The asset is attributed to where the picture first appears.
    assert data["images"][0]["page_no"] == 1

    _assert_placeholders_match_images(data)
    # Deduplication is not a degradation, so it must not surface a warning.
    assert data["warnings"] == []


def test_parse_pdf_does_not_render_scanned_pages_past_the_image_cap(client, monkeypatch):
    """Rasterizing a page the collector would only discard is pure waste:
    past the cap the render must not happen at all (OCR_ENGINE=none, so
    nothing else needs the PNG)."""
    from app import config

    monkeypatch.setattr(config, "MAX_IMAGES_PER_DOC", 2)
    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_NONE)

    renders = []
    original_get_pixmap = pymupdf.Page.get_pixmap

    def counting_get_pixmap(self, *args, **kwargs):
        renders.append(self.number)
        return original_get_pixmap(self, *args, **kwargs)

    monkeypatch.setattr(pymupdf.Page, "get_pixmap", counting_get_pixmap)

    response = post_parse(client, "scanned5.pdf", make_scanned_pdf_bytes(pages=5), "pdf")

    assert response.status_code == 200
    data = response.json()["data"]
    assert len(data["images"]) == 2
    assert len(renders) == 2, f"only the pages that fit may be rendered, rendered={renders}"
    # The 3 pages over the cap are still reported as skipped, exactly as before.
    assert len(data["warnings"]) == 3
    assert all("limit" in warning for warning in data["warnings"])


def test_parse_pdf_still_renders_past_the_cap_when_local_ocr_is_on(client, monkeypatch):
    """The cap bounds the images the response carries, not this service's
    ability to read a page: with OCR_ENGINE=paddle every scanned page is
    still rendered so its text can be recovered."""
    from app import config
    from app.ocr import engine as ocr_engine_module

    monkeypatch.setattr(config, "MAX_IMAGES_PER_DOC", 1)
    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_PADDLE)

    class StubOcrEngine(ocr_engine_module.OcrEngine):
        def recognize(self, png_bytes: bytes, page_no: int):
            assert png_bytes, "the engine must receive a real render"
            return f"ocr text of page {page_no}"

    monkeypatch.setattr(ocr_engine_module, "get_ocr_engine", lambda: StubOcrEngine())
    monkeypatch.setattr("app.parsers.pdf.get_ocr_engine", lambda: StubOcrEngine())

    response = post_parse(client, "scanned3.pdf", make_scanned_pdf_bytes(pages=3), "pdf")

    assert response.status_code == 200
    data = response.json()["data"]
    assert len(data["images"]) == 1, "the image cap still applies to the response"
    for page in data["pages"]:
        assert page["ocr_source"] == "paddle"
        assert page["text"] == f"ocr text of page {page['page_no']}"
