from tests.conftest import make_pdf_bytes, post_parse


def test_parse_pdf_returns_expected_structure(client):
    content = make_pdf_bytes("Hello kb-rag PDF")

    response = post_parse(client, "sample.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    assert body["request_id"]
    data = body["data"]
    assert "Hello kb-rag PDF" in data["markdown"]
    assert len(data["pages"]) == 1
    assert data["pages"][0]["page_no"] == 1
    assert "Hello kb-rag PDF" in data["pages"][0]["text"]
    assert data["images"] == []
