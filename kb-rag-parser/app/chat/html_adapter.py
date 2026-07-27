"""HTML DOM adapter for chat log exports (M8-CONTRACTS.md §0.2).

Uses only the standard library's ``html.parser.HTMLParser`` (no bs4: a
minimal selector engine over a small in-memory tree is enough for the
fixed, mapping-profile-declared selector schema this adapter needs, so the
heavier optional dependency is never pulled in). Security posture, per
requirement §4.2 and the existing security.py guardrails:

- ``<script>``/``<style>`` element bodies are dropped while building the
  tree (never surfaced as message text, never executed -- this parser
  never executes anything).
- No remote resource is ever fetched: an ``<img>`` node is only inspected
  for *presence* (to emit the fixed [IMAGE] placeholder), its ``src`` is
  never read or dereferenced. This is true of every parser in this
  service (no HTTP client is implemented anywhere), but is called out here
  because HTML is the one format whose raw content invites that mistake.
- ``html.parser`` is not an XML parser, so the XXE hardening in
  app/security.py (which targets ``xml.etree``/``lxml``) does not apply
  here and is not needed: HTML entities are just text substitution.

Selectors in the mapping profile's ``html:`` section (M8-CONTRACTS.md
§0.2) are a small CSS subset: ``tag``, ``.class``, ``#id``, and
``tag.class`` combinations (e.g. ``div.message``) -- enough to address the
built-in "留痕" HTML export template (app/mappings/liuhen_html.yml)
without a full CSS engine.

Author: owlzhangfq@gmail.com
"""

import logging
import re
from html.parser import HTMLParser
from typing import Dict, List, Optional, Tuple, Union

from app.chat.normalize import IMAGE_PLACEHOLDER_TEXT, MSG_TYPE_IMAGE, MSG_TYPE_TEXT, parse_send_time_ms
from app.errors import ChatMappingError, ErrorCode
from app.models import ChatMessage, ChatSkippedStats

logger = logging.getLogger(__name__)

# html: selectors required for the message-node schema; "image"/"voice"/
# "video" are optional refinements with sane tag-name defaults below.
_REQUIRED_SELECTORS = ("message", "sender", "time", "content")
_DEFAULT_IMAGE_SELECTOR = "img"
_DEFAULT_VOICE_SELECTOR = "audio"
_DEFAULT_VIDEO_SELECTOR = "video"

# Element bodies never surfaced as message text (M8-CONTRACTS.md §0.2
# "剥离 script/style").
_STRIPPED_ELEMENTS = frozenset({"script", "style"})

_VOID_ELEMENTS = frozenset(
    {"br", "img", "hr", "input", "meta", "link", "area", "base", "col", "embed", "source", "track", "wbr"}
)

_SELECTOR_PATTERN = re.compile(r"^([a-zA-Z][a-zA-Z0-9]*)?((?:[.#][\w-]+)*)$")
_SELECTOR_PART_PATTERN = re.compile(r"[.#][\w-]+")


class _Node:
    """One element in the minimal in-memory DOM tree built for selector
    matching; children are either nested _Node instances or raw text str."""

    __slots__ = ("tag", "attrs", "children")

    def __init__(self, tag: str, attrs: Dict[str, str]) -> None:
        self.tag = tag
        self.attrs = attrs
        self.children: List[Union["_Node", str]] = []


class _TreeBuilder(HTMLParser):
    """Builds a _Node tree from HTML, dropping script/style bodies and
    tolerating unclosed/mismatched tags (real-world chat export HTML is
    not always well-formed)."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node("#root", {})
        self._stack: List[_Node] = [self.root]
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        node = _Node(tag, {k: (v or "") for k, v in attrs})
        self._stack[-1].children.append(node)
        if tag in _STRIPPED_ELEMENTS:
            self._skip_depth += 1
        if tag not in _VOID_ELEMENTS:
            self._stack.append(node)

    def handle_startendtag(self, tag: str, attrs) -> None:
        self._stack[-1].children.append(_Node(tag, {k: (v or "") for k, v in attrs}))

    def handle_endtag(self, tag: str) -> None:
        for depth in range(len(self._stack) - 1, 0, -1):
            if self._stack[depth].tag == tag:
                if tag in _STRIPPED_ELEMENTS:
                    self._skip_depth = max(0, self._skip_depth - 1)
                del self._stack[depth:]
                return

    def handle_data(self, data: str) -> None:
        if self._skip_depth > 0:
            return
        self._stack[-1].children.append(data)


def _parse_selector(selector: str) -> Tuple[Optional[str], List[str], Optional[str]]:
    match = _SELECTOR_PATTERN.match(selector.strip())
    if not match:
        raise ChatMappingError(f"invalid html: selector '{selector}'")
    tag = match.group(1) or None
    classes: List[str] = []
    node_id: Optional[str] = None
    for part in _SELECTOR_PART_PATTERN.findall(match.group(2) or ""):
        if part.startswith("."):
            classes.append(part[1:])
        else:
            node_id = part[1:]
    return tag, classes, node_id


def _node_matches(node: _Node, tag: Optional[str], classes: List[str], node_id: Optional[str]) -> bool:
    if tag and node.tag != tag:
        return False
    if node_id and node.attrs.get("id") != node_id:
        return False
    if classes:
        node_classes = set((node.attrs.get("class") or "").split())
        if not set(classes).issubset(node_classes):
            return False
    return True


def _find_all(root: _Node, selector: str) -> List[_Node]:
    tag, classes, node_id = _parse_selector(selector)
    found: List[_Node] = []

    def walk(node: _Node) -> None:
        for child in node.children:
            if isinstance(child, _Node):
                if _node_matches(child, tag, classes, node_id):
                    found.append(child)
                walk(child)

    walk(root)
    return found


def _find_first(root: _Node, selector: str) -> Optional[_Node]:
    tag, classes, node_id = _parse_selector(selector)

    def walk(node: _Node) -> Optional[_Node]:
        for child in node.children:
            if isinstance(child, _Node):
                if _node_matches(child, tag, classes, node_id):
                    return child
                found = walk(child)
                if found is not None:
                    return found
        return None

    return walk(root)


def _text_of(node: Optional[_Node]) -> str:
    if node is None:
        return ""
    parts: List[str] = []

    def walk(n: _Node) -> None:
        for child in n.children:
            if isinstance(child, str):
                parts.append(child)
            else:
                walk(child)

    walk(node)
    return "".join(parts).strip()


def parse_html_messages(
    html_text: str, selectors: Dict[str, str], session_id: str
) -> Tuple[List[ChatMessage], ChatSkippedStats]:
    """Parse HTML chat log text into ChatMessage entries plus skipped stats.

    Raises ChatMappingError if required selectors are missing, or if the
    'message' selector matches zero nodes (almost certainly the wrong
    selector/template for this file, not an actually-empty export).
    """
    missing = [key for key in _REQUIRED_SELECTORS if not selectors.get(key)]
    if missing:
        logger.error(
            "html parse failed, errorCode=%s, reason=missing_selectors, missing=%s",
            ErrorCode.PARSE_FAILED,
            missing,
        )
        raise ChatMappingError(f"mapping profile 'html:' section is missing required selector(s): {missing}")

    builder = _TreeBuilder()
    builder.feed(html_text)
    builder.close()

    message_nodes = _find_all(builder.root, selectors["message"])
    if not message_nodes:
        logger.error(
            "html parse failed, errorCode=%s, reason=message_selector_matched_nothing, selector=%s",
            ErrorCode.PARSE_FAILED,
            selectors["message"],
        )
        raise ChatMappingError(
            f"html: selector message='{selectors['message']}' matched no nodes; "
            "check the file is a supported HTML chat export format, or supply a custom "
            "'html:' selector via mapping_profile/profile_yaml"
        )

    voice_selector = selectors.get("voice", _DEFAULT_VOICE_SELECTOR)
    video_selector = selectors.get("video", _DEFAULT_VIDEO_SELECTOR)
    image_selector = selectors.get("image", _DEFAULT_IMAGE_SELECTOR)

    messages: List[ChatMessage] = []
    skipped = ChatSkippedStats()
    seq = 0

    for node in message_nodes:
        if voice_selector and _find_first(node, voice_selector) is not None:
            skipped.voice += 1
            logger.info("html message skipped, reason=msg_type_excluded, msgType=voice")
            continue
        if video_selector and _find_first(node, video_selector) is not None:
            skipped.video += 1
            logger.info("html message skipped, reason=msg_type_excluded, msgType=video")
            continue

        send_time_text = _text_of(_find_first(node, selectors["time"]))
        send_time_ms = parse_send_time_ms(send_time_text) if send_time_text else None
        if send_time_ms is None:
            skipped.other += 1
            logger.info("html message skipped, reason=unparseable_send_time")
            continue

        sender = _text_of(_find_first(node, selectors["sender"]))
        content_node = _find_first(node, selectors["content"])
        has_image = image_selector and _find_first(node, image_selector) is not None
        if has_image:
            content = IMAGE_PLACEHOLDER_TEXT
            msg_type = MSG_TYPE_IMAGE
        else:
            content = _text_of(content_node)
            msg_type = MSG_TYPE_TEXT

        seq += 1
        messages.append(
            ChatMessage(
                msg_id=f"{session_id}-{seq}",
                sender=sender,
                is_self=False,
                send_time=send_time_ms,
                msg_type=msg_type,
                content=content,
            )
        )

    return messages, skipped
