"""Value normalization for raw chat log cell values (M3-CONTRACTS.md §2.2):
boolean coercion for is_self, epoch-millisecond normalization for
send_time (accepts epoch seconds, epoch milliseconds, a native
datetime.datetime as produced by openpyxl for date-formatted cells, or a
handful of common string formats), and msg_type bucketing.

Author: owlzhangfq@gmail.com
"""

import re
from datetime import datetime
from typing import Optional

_TRUE_TOKENS = frozenset({"1", "true", "yes", "y", "是", "t"})

# Below this a numeric timestamp is assumed to be epoch seconds; at or
# above, epoch milliseconds. 10**11 seconds is year ~5138 (above any
# realistic seconds value) and 10**11 milliseconds is 1973-03-03 (below any
# realistic milliseconds value for a chat export), so the two ranges never
# overlap in practice.
_SECONDS_MS_BOUNDARY = 10**11

# Common string timestamp formats tried in order; the first one that
# parses without raising wins.
_STRING_TIME_FORMATS = (
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d %H:%M",
    "%Y/%m/%d %H:%M:%S",
    "%Y/%m/%d %H:%M",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%d",
)

_VOICE_TOKENS = ("voice", "语音", "audio")
_VIDEO_TOKENS = ("video", "视频")
_IMAGE_TOKENS = ("image", "图片", "照片", "picture", "photo")
_TEXT_TOKENS = ("text", "文本", "文字")

# WeChat/MemoTrace-style numeric type codes. These are the widely
# documented public conventions and have NOT been validated against a real
# export sample yet -- recalibrate once one is available (M3-CONTRACTS.md
# §2.2 "⚠️ 真实样例待验证").
_NUMERIC_VOICE_CODES = frozenset({34})
_NUMERIC_VIDEO_CODES = frozenset({43, 62})
_NUMERIC_IMAGE_CODES = frozenset({3})
_NUMERIC_TEXT_CODES = frozenset({1})

MSG_TYPE_TEXT = "text"
MSG_TYPE_IMAGE = "image"
MSG_TYPE_VOICE = "voice"
MSG_TYPE_VIDEO = "video"
MSG_TYPE_OTHER = "other"


def coerce_is_self(raw_value: object) -> bool:
    """Best-effort boolean coercion; anything unrecognized defaults to False."""
    if raw_value is None:
        return False
    return str(raw_value).strip().lower() in _TRUE_TOKENS


def parse_send_time_ms(raw_value: object) -> Optional[int]:
    """Normalize a raw send_time cell value to epoch milliseconds.

    Returns None if raw_value is missing or matches none of the supported
    representations; the caller is responsible for counting that as a
    skipped message (M3-CONTRACTS.md §2.2 "无法解析则该消息落 skipped.other").
    """
    if raw_value is None:
        return None
    if isinstance(raw_value, datetime):
        return int(raw_value.timestamp() * 1000)

    text = str(raw_value).strip()
    if not text:
        return None

    numeric = _try_parse_number(text)
    if numeric is not None:
        return int(numeric) if numeric >= _SECONDS_MS_BOUNDARY else int(numeric * 1000)

    for fmt in _STRING_TIME_FORMATS:
        try:
            return int(datetime.strptime(text, fmt).timestamp() * 1000)
        except ValueError:
            continue
    return None


def _try_parse_number(text: str) -> Optional[float]:
    try:
        return float(text)
    except ValueError:
        return None


def classify_msg_type(raw_value: object) -> str:
    """Bucket a raw msg_type/type_name source value into
    text|image|voice|video|other.

    A missing/blank source value defaults to "text": a chat export with no
    type column at all is assumed to contain plain text messages, not
    unclassifiable ones.
    """
    if raw_value is None:
        return MSG_TYPE_TEXT
    text = str(raw_value).strip()
    if not text:
        return MSG_TYPE_TEXT

    lowered = text.lower()
    for token in _VOICE_TOKENS:
        if _token_matches(lowered, token):
            return MSG_TYPE_VOICE
    for token in _VIDEO_TOKENS:
        if _token_matches(lowered, token):
            return MSG_TYPE_VIDEO
    for token in _IMAGE_TOKENS:
        if _token_matches(lowered, token):
            return MSG_TYPE_IMAGE
    for token in _TEXT_TOKENS:
        if _token_matches(lowered, token):
            return MSG_TYPE_TEXT

    numeric = _try_parse_int(text)
    if numeric is not None:
        if numeric in _NUMERIC_VOICE_CODES:
            return MSG_TYPE_VOICE
        if numeric in _NUMERIC_VIDEO_CODES:
            return MSG_TYPE_VIDEO
        if numeric in _NUMERIC_IMAGE_CODES:
            return MSG_TYPE_IMAGE
        if numeric in _NUMERIC_TEXT_CODES:
            return MSG_TYPE_TEXT
    return MSG_TYPE_OTHER


def _try_parse_int(text: str) -> Optional[int]:
    try:
        return int(float(text))
    except ValueError:
        return None


def _token_matches(lowered_text: str, token: str) -> bool:
    """True if token occurs in lowered_text as a whole ascii word, or as a
    plain substring for non-ascii (CJK) tokens where regex word boundaries
    don't apply -- avoids false positives like "text" inside "context"
    while still matching compound Chinese labels like "语音消息"."""
    if token.isascii():
        return re.search(rf"\b{re.escape(token)}\b", lowered_text) is not None
    return token in lowered_text
