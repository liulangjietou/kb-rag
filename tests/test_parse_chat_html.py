"""M8 HTML chat log adapter tests (M8-CONTRACTS.md §0.2): built-in liuhen
DOM template, image-message placeholder, voice/video skip, script/style
stripping, custom selector override via profile_yaml, and the
message-selector-matched-nothing fast-fail.
"""

from tests.conftest import post_parse_chat


def _html_bytes(body: str) -> bytes:
    return f"<html><body>{body}</body></html>".encode("utf-8")


def test_parse_chat_html_liuhen_template_positive(client):
    body = """
    <div class="message">
      <span class="sender">张三</span>
      <span class="time">2024-01-01 10:00:00</span>
      <div class="content">你好，最近怎么样？</div>
    </div>
    <div class="message">
      <span class="sender">李四</span>
      <span class="time">2024-01-01 10:05:00</span>
      <div class="content">挺好的，你呢？</div>
    </div>
    """
    content = _html_bytes(body)

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    resp_body = response.json()
    assert resp_body["code"] == "OK"
    data = resp_body["data"]

    assert len(data["sessions"]) == 1
    session = data["sessions"][0]
    assert session["session_id"] == "chat"
    assert len(session["messages"]) == 2

    first = session["messages"][0]
    assert first["sender"] == "张三"
    assert first["msg_type"] == "text"
    assert first["content"] == "你好，最近怎么样？"
    assert first["send_time"] > 0  # exact value is timezone-dependent; just must parse

    assert data["skipped"] == {"voice": 0, "video": 0, "other": 0}


def test_parse_chat_html_image_message_becomes_placeholder(client):
    body = """
    <div class="message">
      <span class="sender">张三</span>
      <span class="time">2024-01-01 10:00:00</span>
      <div class="content"><img src="http://example.com/should-not-be-fetched.png"/></div>
    </div>
    """
    content = _html_bytes(body)

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    resp_body = response.json()
    assert resp_body["code"] == "OK"
    message = resp_body["data"]["sessions"][0]["messages"][0]
    assert message["msg_type"] == "image"
    assert message["content"] == "[IMAGE]"


def test_parse_chat_html_voice_and_video_messages_are_skipped_but_counted(client):
    body = """
    <div class="message">
      <span class="sender">A</span>
      <span class="time">2024-01-01 10:00:00</span>
      <div class="content">text message</div>
    </div>
    <div class="message">
      <span class="sender">B</span>
      <span class="time">2024-01-01 10:01:00</span>
      <div class="content"><audio src="voice.amr"></audio></div>
    </div>
    <div class="message">
      <span class="sender">C</span>
      <span class="time">2024-01-01 10:02:00</span>
      <div class="content"><video src="clip.mp4"></video></div>
    </div>
    """
    content = _html_bytes(body)

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    resp_body = response.json()
    data = resp_body["data"]
    messages = data["sessions"][0]["messages"]
    assert len(messages) == 1
    assert messages[0]["sender"] == "A"
    assert data["skipped"] == {"voice": 1, "video": 1, "other": 0}


def test_parse_chat_html_strips_script_and_style_content(client):
    body = """
    <script>alert('should never be executed or surfaced as text');</script>
    <style>.message { color: red; }</style>
    <div class="message">
      <span class="sender">张三</span>
      <span class="time">2024-01-01 10:00:00</span>
      <div class="content">hello<script>evil()</script></div>
    </div>
    """
    content = _html_bytes(body)

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    resp_body = response.json()
    message = resp_body["data"]["sessions"][0]["messages"][0]
    assert message["content"] == "hello"
    assert "alert" not in resp_body["data"]["sessions"][0]["messages"][0]["content"]
    assert "evil" not in resp_body["data"]["sessions"][0]["messages"][0]["content"]


def test_parse_chat_html_custom_selectors_via_profile_yaml(client):
    body = """
    <li class="chat-row">
      <b class="who">alice</b>
      <i class="at">2024-01-01 10:00:00</i>
      <p class="msg">hi there</p>
    </li>
    """
    content = _html_bytes(body)
    profile_yaml = (
        "html:\n"
        "  message: li.chat-row\n"
        "  sender: b.who\n"
        "  time: i.at\n"
        "  content: p.msg\n"
    )

    response = post_parse_chat(client, "chat.html", content, "html", profile_yaml=profile_yaml)

    assert response.status_code == 200
    resp_body = response.json()
    assert resp_body["code"] == "OK"
    message = resp_body["data"]["sessions"][0]["messages"][0]
    assert message["sender"] == "alice"
    assert message["content"] == "hi there"


def test_parse_chat_html_message_selector_matches_nothing_fails(client):
    content = _html_bytes("<div class='not-a-message'>irrelevant</div>")

    response = post_parse_chat(client, "chat.html", content, "html")

    assert response.status_code == 200
    resp_body = response.json()
    assert resp_body["code"] == "PARSE_FAILED"
    assert resp_body["data"] is None
    assert "selector" in resp_body["message"]
