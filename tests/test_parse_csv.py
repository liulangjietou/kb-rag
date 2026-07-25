from tests.conftest import make_csv_bytes, post_parse


def test_parse_csv_returns_expected_structure(client):
    content = make_csv_bytes()

    response = post_parse(client, "sample.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    assert "Alice" in data["markdown"]
    assert "Name" in data["markdown"]
    assert len(data["pages"]) == 1
    assert "Bob" in data["pages"][0]["text"]
    assert data["images"] == []
