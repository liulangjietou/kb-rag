"""M8 mapping-profile-over-the-wire tests (M8-CONTRACTS.md §0.7):
profile_yaml, when supplied, takes priority over mapping_profile resolving
to a local app/mappings/*.yml file -- kb-rag-server passes this once
profiles live in t_kb_source_mapping rather than only as local files.
"""

from tests.conftest import make_chat_csv_bytes, post_parse_chat


def test_profile_yaml_takes_priority_over_nonexistent_local_mapping_profile(client):
    header = ["from", "when", "body"]
    rows = [["alice", "1737800000", "hello via inline profile"]]
    content = make_chat_csv_bytes(header, rows)

    profile_yaml = "sender:\n  - from\nsend_time:\n  - when\ncontent:\n  - body\n"

    response = post_parse_chat(
        client,
        "chat.csv",
        content,
        "csv",
        mapping_profile="this_profile_file_does_not_exist_on_disk",
        profile_yaml=profile_yaml,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    message = body["data"]["sessions"][0]["messages"][0]
    assert message["sender"] == "alice"
    assert message["content"] == "hello via inline profile"


def test_profile_yaml_invalid_yaml_fails_with_actionable_error(client):
    header = ["from", "when", "body"]
    rows = [["alice", "1737800000", "hi"]]
    content = make_chat_csv_bytes(header, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv", profile_yaml="not: [valid: yaml")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None


def test_parse_chat_html_default_profile_is_used_when_not_specified(client):
    content = "<div class='message'><span class='sender'>a</span><span class='time'>2024-01-01 10:00:00</span><div class='content'>hi</div></div>".encode("utf-8")

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    assert body["data"]["sessions"][0]["messages"][0]["content"] == "hi"
