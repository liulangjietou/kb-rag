"""TXT line-template adapter for chat log exports (M8-CONTRACTS.md §0.1).

A TXT export is read line by line against an ordered list of line-header
regex templates (``TxtLinePattern``, app/chat/mapping.py), each carrying
named capture groups ``send_time``/``sender`` and, optionally, ``content``
(when a template's message body can start on the same line as its header,
e.g. the built-in wechat_pc template). Two built-in templates ship in
app/mappings/liuhen_txt.yml:

  - liuhen: ``YYYY-MM-DD HH:MM:SS 发送人`` on its own line, message body on
    the following line(s) (MemoTrace/"留痕" TXT export style).
  - wechat_pc: ``发送人 (YYYY-MM-DD HH:MM:SS):`` header, body inline or on
    following line(s) (WeChat PC client export style).

A line that matches no configured template is treated as continuation
content of the current message (multi-line merge into the previous entry,
per contract), unless no message has started yet, in which case it counts
toward the unmatched-line ratio: a TXT file whose lines mostly don't match
any configured template almost certainly means the wrong template/profile
was picked, so the parser fails fast with an actionable error rather than
silently emitting near-empty sessions (M8-CONTRACTS.md §0.1
"不匹配行占比>30% -> 解析失败").

Author: owlzhangfq@gmail.com
"""

import logging
from typing import List, Optional, Tuple

from app.chat.mapping import TxtLinePattern
from app.chat.normalize import MSG_TYPE_TEXT, parse_send_time_ms
from app.config import TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD
from app.errors import ChatMappingError, ErrorCode
from app.models import ChatMessage

logger = logging.getLogger(__name__)


def parse_txt_messages(
    text: str, patterns: List[TxtLinePattern], session_id: str
) -> Tuple[List[ChatMessage], int]:
    """Parse TXT chat log text into ChatMessage entries.

    Returns (messages, skipped_other_count) where skipped_other_count
    mirrors the csv/xlsx "unparseable send_time" skip bucket (a line
    structurally matched a header template but its send_time capture did
    not parse in any known format) -- kept separate from the file-level
    unmatched-line fast-fail below, which is about the *template* not
    matching at all, not about a per-message value being malformed.

    Raises ChatMappingError if no txt: patterns are configured, or if the
    ratio of lines matching no configured template exceeds
    TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD.
    """
    if not patterns:
        logger.error(
            "txt parse failed, errorCode=%s, reason=no_patterns_configured",
            ErrorCode.PARSE_FAILED,
        )
        raise ChatMappingError("mapping profile has no 'txt:' line patterns configured")

    messages: List[ChatMessage] = []
    current: Optional[dict] = None
    seq = 0
    skipped_other = 0
    considered_lines = 0
    unmatched_lines = 0

    def flush() -> None:
        nonlocal current, seq
        if current is None:
            return
        content = "\n".join(current["content_lines"]).strip()
        seq += 1
        messages.append(
            ChatMessage(
                msg_id=f"{session_id}-{seq}",
                sender=current["sender"],
                is_self=False,
                send_time=current["send_time"],
                msg_type=MSG_TYPE_TEXT,
                content=content,
            )
        )
        current = None

    for line in text.splitlines():
        if line.strip() == "":
            continue  # blank lines are structural separators only, never counted
        considered_lines += 1

        header_match = _match_header(line, patterns)
        if header_match is None:
            if current is not None:
                current["content_lines"].append(line)
            else:
                unmatched_lines += 1
            continue

        send_time_ms = parse_send_time_ms(header_match.group("send_time"))
        if send_time_ms is None:
            # The header regex itself matched, but its captured timestamp
            # doesn't parse -- a per-message data problem, not a
            # template/format mismatch, so it does not count toward the
            # unmatched-line ratio; mirrors the csv/xlsx skipped.other bucket.
            logger.info("txt line skipped, reason=unparseable_send_time, line=%r", line)
            skipped_other += 1
            continue

        flush()
        content_group = header_match.groupdict().get("content") or ""
        current = {
            "sender": (header_match.group("sender") or "").strip(),
            "send_time": send_time_ms,
            "content_lines": [content_group] if content_group.strip() else [],
        }
    flush()

    if considered_lines > 0:
        unmatched_ratio = unmatched_lines / considered_lines
        if unmatched_ratio > TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD:
            logger.error(
                "txt parse failed, errorCode=%s, reason=unmatched_line_ratio_exceeded, "
                "unmatchedLines=%d, consideredLines=%d, threshold=%.2f",
                ErrorCode.PARSE_FAILED,
                unmatched_lines,
                considered_lines,
                TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD,
            )
            raise ChatMappingError(
                f"{unmatched_lines}/{considered_lines} lines "
                f"({unmatched_ratio:.0%}) matched no configured txt: line template "
                f"(threshold {TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD:.0%}); "
                "check the file is a supported TXT chat export format, or supply a "
                "custom 'txt:' pattern via mapping_profile/profile_yaml"
            )

    return messages, skipped_other


def _match_header(line: str, patterns: List[TxtLinePattern]):
    for pattern in patterns:
        match = pattern.compiled.match(line)
        if match:
            return match
    return None
