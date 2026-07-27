"""M3 docx multimodal test (M3-CONTRACTS.md §2.1): embedded image
extraction from a docx's word/media/* zip entries, and placeholder/images
consistency.
"""

import re

from tests.conftest import make_docx_with_image_bytes, post_parse

_PLACEHOLDER_PATTERN = re.compile(r"\[\[IMAGE:([^\]]+)\]\]")


def test_parse_docx_embedded_image_is_extracted(client):
    content = make_docx_with_image_bytes()

    response = post_parse(client, "with_image.docx", content, "docx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["pages"]) == 1
    assert data["pages"][0]["scanned"] is False

    assert len(data["images"]) == 1
    image = data["images"][0]
    assert image["kind"] == "embedded"
    assert image["media_type"] == "image/png"
    assert image["page_no"] == 1
    assert image["content_base64"]

    placeholder_ids = _PLACEHOLDER_PATTERN.findall(data["markdown"])
    assert placeholder_ids == [image["image_id"]]
