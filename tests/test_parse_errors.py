from tests.conftest import post_parse


def test_parse_rejects_unsupported_file_ext(client):
    response = post_parse(client, "sample.exe", b"whatever", "exe")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
    assert "unsupported" in body["message"].lower()
