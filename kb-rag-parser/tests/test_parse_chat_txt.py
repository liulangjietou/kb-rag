"""M8 TXT chat log adapter tests (M8-CONTRACTS.md §0.1): built-in liuhen /
wechat_pc line templates, multi-line merge, custom regex override via
profile_yaml, and the >30% unmatched-line-ratio fast-fail.
"""

from tests.conftest import post_parse_chat


def _txt_bytes(text: str) -> bytes:
    return text.encode("utf-8")


def test_parse_chat_txt_liuhen_template_multiline_merge(client):
    content = _txt_bytes(
        "2024-01-01 10:00:00 张三\n"
        "你好，最近怎么样？\n"
        "\n"
        "2024-01-01 10:05:00 李四\n"
        "挺好的，你呢？\n"
        "第二行内容\n"
    )

    response = post_parse_chat(client, "chat.txt", content, "txt")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["sessions"]) == 1
    session = data["sessions"][0]
    assert session["session_id"] == "chat"
    assert len(session["messages"]) == 2

    first = session["messages"][0]
    assert first["sender"] == "张三"
    assert first["msg_type"] == "text"
    assert first["content"] == "你好，最近怎么样？"

    second = session["messages"][1]
    assert second["sender"] == "李四"
    # multi-line message body merged into the previous (single) entry
    assert second["content"] == "挺好的，你呢？\n第二行内容"

    assert data["skipped"] == {"voice": 0, "video": 0, "other": 0}


def test_parse_chat_txt_wechat_pc_template_inline_content(client):
    content = _txt_bytes(
        "张三 (2024-01-01 10:00:00): 你好\n"
        "李四 (2024-01-01 10:05:00): 挺好的，你呢？\n"
    )

    response = post_parse_chat(client, "chat.txt", content, "txt")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    messages = body["data"]["sessions"][0]["messages"]
    assert len(messages) == 2
    assert messages[0]["sender"] == "张三"
    assert messages[0]["content"] == "你好"
    assert messages[1]["sender"] == "李四"
    assert messages[1]["content"] == "挺好的，你呢？"


def test_parse_chat_txt_custom_regex_via_profile_yaml_overrides_builtin(client):
    # A deliberately different line shape from both built-in templates:
    # "[2024-01-01 10:00:00] alice >> hello there"
    content = _txt_bytes("[2024-01-01 10:00:00] alice >> hello there\n")
    profile_yaml = (
        "txt:\n"
        "  patterns:\n"
        "    - name: custom\n"
        "      regex: '^\\[(?P<send_time>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] "
        "(?P<sender>\\S+) >> (?P<content>.*)$'\n"
    )

    response = post_parse_chat(client, "chat.txt", content, "txt", profile_yaml=profile_yaml)

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    messages = body["data"]["sessions"][0]["messages"]
    assert len(messages) == 1
    assert messages[0]["sender"] == "alice"
    assert messages[0]["content"] == "hello there"


def test_parse_chat_txt_wrong_format_fails_with_actionable_error(client):
    # Plain prose with no line ever matching a configured header template.
    content = _txt_bytes(
        "This is just some random text file.\n"
        "It has multiple lines.\n"
        "None of them look like a chat log export at all.\n"
        "Not even close to the expected line templates.\n"
    )

    response = post_parse_chat(client, "notes.txt", content, "txt")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
    assert "line template" in body["message"]


def test_parse_chat_txt_unparseable_timestamp_is_skipped_as_other(client):
    content = _txt_bytes(
        "2024-13-99 99:99:99 张三\n"
        "this header matched the template shape but the date is not real\n"
        "\n"
        "2024-01-01 10:00:00 李四\n"
        "a genuinely valid message\n"
    )

    response = post_parse_chat(client, "chat.txt", content, "txt")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    messages = data["sessions"][0]["messages"]
    assert len(messages) == 1
    assert messages[0]["sender"] == "李四"
    assert data["skipped"]["other"] == 1
