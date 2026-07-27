from tests.conftest import make_xlsx_bytes, post_parse


def test_parse_xlsx_returns_expected_structure(client):
    content = make_xlsx_bytes()

    response = post_parse(client, "sample.xlsx", content, "xlsx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    assert "Sheet1" in data["markdown"]
    assert "Alice" in data["markdown"]
    assert len(data["pages"]) == 1
    assert "Alice" in data["pages"][0]["text"]
    assert data["images"] == []
