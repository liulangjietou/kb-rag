from tests.conftest import post_parse


def test_parse_txt_returns_expected_structure(client):
    content = "Hello kb-rag TXT\nsecond line".encode("utf-8")

    response = post_parse(client, "sample.txt", content, "txt")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    assert data["markdown"] == "Hello kb-rag TXT\nsecond line"
    assert len(data["pages"]) == 1
    assert data["pages"][0]["page_no"] == 1
    assert data["images"] == []


def test_parse_md_returns_expected_structure(client):
    content = "# Heading\n\nHello kb-rag MD".encode("utf-8")

    response = post_parse(client, "sample.md", content, "md")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    assert "# Heading" in data["markdown"]
    assert "Hello kb-rag MD" in data["markdown"]
    assert data["images"] == []
