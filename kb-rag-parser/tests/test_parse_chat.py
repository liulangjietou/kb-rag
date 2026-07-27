"""M3 chat log parsing tests (M3-CONTRACTS.md §2.2):
POST /api/v1/parse/chat with the built-in "memotrace" mapping profile.
"""

from tests.conftest import make_chat_csv_bytes, make_chat_xlsx_bytes, post_parse_chat

_HEADER = ["room_name", "NickName", "Sender", "IsSender", "CreateTime", "Type", "StrContent"]


def test_parse_chat_csv_positive(client):
    rows = [
        ["room_a", "Alice's Room", "alice", "1", "1737800000", "1", "hello there"],
        ["room_a", "Alice's Room", "bob", "0", "1737800060", "1", "hi alice"],
    ]
    content = make_chat_csv_bytes(_HEADER, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["sessions"]) == 1
    session = data["sessions"][0]
    assert session["session_id"] == "room_a"
    assert session["session_name"] == "Alice's Room"
    assert len(session["messages"]) == 2

    first = session["messages"][0]
    assert first["sender"] == "alice"
    assert first["is_self"] is True
    assert first["msg_type"] == "text"
    assert first["content"] == "hello there"
    # epoch seconds -> milliseconds
    assert first["send_time"] == 1737800000000

    second = session["messages"][1]
    assert second["is_self"] is False

    assert data["skipped"] == {"voice": 0, "video": 0, "other": 0}


def test_parse_chat_xlsx_positive(client):
    rows = [
        ["room_b", "Team Chat", "carol", "1", "1737800000000", "1", "xlsx hello"],
    ]
    content = make_chat_xlsx_bytes(_HEADER, rows)

    response = post_parse_chat(client, "chat.xlsx", content, "xlsx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    assert len(data["sessions"]) == 1
    session = data["sessions"][0]
    assert session["session_id"] == "room_b"
    assert len(session["messages"]) == 1
    message = session["messages"][0]
    assert message["content"] == "xlsx hello"
    # epoch milliseconds passed straight through
    assert message["send_time"] == 1737800000000


def test_parse_chat_missing_content_column_fails(client):
    header = ["room_name", "NickName", "Sender", "IsSender", "CreateTime", "Type"]  # no content candidate
    rows = [["room_a", "Alice's Room", "alice", "1", "1737800000", "1"]]
    content = make_chat_csv_bytes(header, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
    assert "content" in body["message"]


def test_parse_chat_send_time_formats_are_all_normalized_to_epoch_ms(client):
    rows = [
        ["room_a", "Room", "alice", "1", "1737800000", "1", "epoch seconds"],
        ["room_a", "Room", "alice", "1", "1737800000000", "1", "epoch millis"],
        ["room_a", "Room", "alice", "1", "2025-01-25 10:13:20", "1", "string datetime"],
    ]
    content = make_chat_csv_bytes(_HEADER, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]

    messages = data["sessions"][0]["messages"]
    assert len(messages) == 3
    assert messages[0]["send_time"] == 1737800000000
    assert messages[1]["send_time"] == 1737800000000
    assert messages[2]["send_time"] > 0  # exact value is timezone-dependent; just must parse


def test_parse_chat_unparseable_send_time_is_skipped_as_other(client):
    rows = [
        ["room_a", "Room", "alice", "1", "not-a-real-timestamp", "1", "bad time"],
        ["room_a", "Room", "alice", "1", "1737800000", "1", "good time"],
    ]
    content = make_chat_csv_bytes(_HEADER, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    data = body["data"]

    assert len(data["sessions"][0]["messages"]) == 1
    assert data["sessions"][0]["messages"][0]["content"] == "good time"
    assert data["skipped"] == {"voice": 0, "video": 0, "other": 1}


def test_parse_chat_voice_and_video_messages_are_skipped_but_counted(client):
    rows = [
        ["room_a", "Room", "alice", "1", "1737800000", "1", "text message"],
        ["room_a", "Room", "alice", "1", "1737800001", "34", "[voice message]"],
        ["room_a", "Room", "alice", "1", "1737800002", "43", "[video message]"],
        ["room_a", "Room", "alice", "1", "1737800003", "3", "[image message]"],
    ]
    content = make_chat_csv_bytes(_HEADER, rows)

    response = post_parse_chat(client, "chat.csv", content, "csv")

    assert response.status_code == 200
    body = response.json()
    data = body["data"]

    messages = data["sessions"][0]["messages"]
    msg_types = [m["msg_type"] for m in messages]
    assert "voice" not in msg_types
    assert "video" not in msg_types
    assert "image" in msg_types
    assert "text" in msg_types
    assert len(messages) == 2  # text + image only

    assert data["skipped"] == {"voice": 1, "video": 1, "other": 0}


def test_parse_chat_unknown_mapping_profile_fails(client):
    content = make_chat_csv_bytes(_HEADER, [["room_a", "Room", "alice", "1", "1737800000", "1", "hi"]])

    response = post_parse_chat(client, "chat.csv", content, "csv", mapping_profile="does_not_exist")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
