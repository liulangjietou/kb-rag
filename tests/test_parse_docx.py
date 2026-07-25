from tests.conftest import make_docx_bytes, post_parse


def test_parse_docx_returns_expected_structure(client):
    content = make_docx_bytes(heading="Title", paragraph="Hello kb-rag DOCX")

    response = post_parse(client, "sample.docx", content, "docx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    assert "Title" in data["markdown"]
    assert "Hello kb-rag DOCX" in data["markdown"]
    # Table content should be rendered too.
    assert "Alice" in data["markdown"]
    assert len(data["pages"]) == 1
    assert "Hello kb-rag DOCX" in data["pages"][0]["text"]
    assert data["images"] == []
