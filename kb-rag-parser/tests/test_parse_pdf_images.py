"""M3 pdf multimodal tests (M3-CONTRACTS.md §2.1): scanned-page detection
and rendering, embedded image extraction, and placeholder/images[]
consistency.
"""

import re

from tests.conftest import make_pdf_with_embedded_image_bytes, make_scanned_pdf_bytes, post_parse

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
