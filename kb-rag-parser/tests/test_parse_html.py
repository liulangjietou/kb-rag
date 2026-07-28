"""Tests for the general HTML parser (M12-CONTRACTS.md section 2).

Author: owlzhangfq@gmail.com
"""

from tests.conftest import post_parse

_SAMPLE_HTML = """<!DOCTYPE html>
<html>
<head>
  <title>kb-rag 指南</title>
  <meta charset="utf-8">
  <style>body { color: red; }</style>
  <script>console.log("tracking");</script>
</head>
<body>
  <h1>快速开始</h1>
  <p>第一段，包含一个 <a href="https://example.com/link">链接文本</a> 与 <b>加粗</b>。</p>
  <h2>安装步骤</h2>
  <ul>
    <li>第一步</li>
    <li>第二步</li>
  </ul>
  <script>alert("in body");</script>
  <noscript>请开启 JS</noscript>
</body>
</html>
"""


def test_parse_html_returns_expected_structure(client):
    response = post_parse(client, "guide.html", _SAMPLE_HTML.encode("utf-8"), "html")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    data = body["data"]
    markdown = data["markdown"]
    # Title becomes the top-level heading, tag headings keep their levels.
    assert markdown.startswith("# kb-rag 指南")
    assert "# 快速开始" in markdown
    assert "## 安装步骤" in markdown
    assert len(data["pages"]) == 1
    assert data["pages"][0]["page_no"] == 1
    assert data["images"] == []


def test_parse_html_strips_invisible_content(client):
    response = post_parse(client, "guide.html", _SAMPLE_HTML.encode("utf-8"), "html")

    markdown = response.json()["data"]["markdown"]
    # script/style/noscript bodies are invisible on the page, so they must
    # not leak into the retrieval corpus.
    assert "console.log" not in markdown
    assert "color: red" not in markdown
    assert "alert(" not in markdown
    assert "请开启 JS" not in markdown


def test_parse_html_keeps_anchor_text_drops_href(client):
    response = post_parse(client, "guide.html", _SAMPLE_HTML.encode("utf-8"), "html")

    markdown = response.json()["data"]["markdown"]
    assert "链接文本" in markdown
    assert "https://example.com/link" not in markdown


def test_parse_html_blocks_become_separate_lines(client):
    content = "<div>alpha</div><div>beta</div>".encode("utf-8")

    response = post_parse(client, "blocks.html", content, "html")

    markdown = response.json()["data"]["markdown"]
    # Adjacent blocks must not run together into one word.
    assert "alphabeta" not in markdown
    assert "alpha" in markdown
    assert "beta" in markdown


def test_parse_htm_extension_registered(client):
    content = "<p>htm works</p>".encode("utf-8")

    response = post_parse(client, "legacy.htm", content, "htm")

    assert response.status_code == 200
    assert "htm works" in response.json()["data"]["markdown"]


def test_parse_html_gbk_encoded(client):
    content = "<html><body><p>中文编码测试</p></body></html>".encode("gbk")

    response = post_parse(client, "gbk.html", content, "html")

    assert response.status_code == 200
    assert "中文编码测试" in response.json()["data"]["markdown"]


def test_parse_html_malformed_markup_is_tolerated(client):
    # The stdlib tokenizer is forgiving: unclosed tags and stray closers
    # still yield the visible text instead of an error.
    content = "<p>unclosed <b>bold</p></i><div>tail".encode("utf-8")

    response = post_parse(client, "broken.html", content, "html")

    assert response.status_code == 200
    markdown = response.json()["data"]["markdown"]
    assert "unclosed" in markdown
    assert "bold" in markdown
    assert "tail" in markdown
