"""Chat log parsing: turns a csv/xlsx chat export into sessions of
messages (M3-CONTRACTS.md §2.2).

Rows are read generically -- csv.DictReader / openpyxl worksheet rows --
into a list of {actual_header_name: raw_value} dicts, independent of which
export tool produced the file; a MappingProfile then resolves each
logical field (session_id, sender, send_time, ...) to whichever actual
header name is present. Onboarding a new export source is therefore a
matter of adding a mappings/*.yml file, never touching this module.

Author: owlzhangfq@gmail.com
"""

import csv
import io
import logging
from typing import Any, Dict, List, Optional

import openpyxl

from app.chat.mapping import MappingProfile
from app.chat.normalize import classify_msg_type, coerce_is_self, parse_send_time_ms
from app.encoding import decode_bytes
from app.errors import ChatMappingError, ErrorCode, UnsupportedFormatError
from app.models import ChatMessage, ChatParseData, ChatSession, ChatSkippedStats
from app.security import ensure_zip_is_safe

logger = logging.getLogger(__name__)

_DEFAULT_SESSION_ID = "default"

# msg_type buckets that are dropped from the output and only counted
# (M3-CONTRACTS.md §2.2 "语音/视频消息跳过"); every other bucket, including
# "other", is kept in sessions[].messages[].
_EXCLUDED_MSG_TYPES = frozenset({"voice", "video"})


def parse_chat(content: bytes, filename: str, file_ext: str, mapping_profile: str) -> ChatParseData:
    """Parse chat log bytes into ChatParseData, or raise a ParseError subclass.

    The single required condition (fast-fail boundary for this endpoint) is
    that the mapping profile resolves a "content" column against the
    file's actual header row; every other logical field degrades
    gracefully (fallback session id, empty sender, msg_type defaulting to
    "text", ...) rather than failing the whole request.
    """
    rows = _read_rows(content, file_ext)
    if not rows:
        return ChatParseData(sessions=[], skipped=ChatSkippedStats())

    profile = MappingProfile.load(mapping_profile)
    header = list(rows[0].keys())
    resolved = profile.resolve(header)

    if resolved.get("content") is None:
        logger.error(
            "chat mapping missing required column, errorCode=%s, profile=%s, field=content",
            ErrorCode.PARSE_FAILED,
            mapping_profile,
        )
        raise ChatMappingError(
            f"mapping profile '{mapping_profile}' could not resolve required column 'content' "
            f"from header {header}"
        )

    sessions: Dict[str, ChatSession] = {}
    session_order: List[str] = []
    skipped = ChatSkippedStats()

    for row_index, row in enumerate(rows):
        if not _has_any_value(row):
            continue  # a fully blank row (common openpyxl used-range padding); not a message

        msg_type = classify_msg_type(_raw(row, resolved.get("msg_type")))
        if msg_type in _EXCLUDED_MSG_TYPES:
            setattr(skipped, msg_type, getattr(skipped, msg_type) + 1)
            logger.info(
                "chat message skipped, reason=msg_type_excluded, msgType=%s, rowIndex=%d",
                msg_type,
                row_index,
            )
            continue

        send_time_ms = parse_send_time_ms(_raw(row, resolved.get("send_time")))
        if send_time_ms is None:
            skipped.other += 1
            logger.info("chat message skipped, reason=unparseable_send_time, rowIndex=%d", row_index)
            continue

        session_id = _value(row, resolved.get("session_id")) or _DEFAULT_SESSION_ID
        session_name = _value(row, resolved.get("session_name")) or session_id
        message = ChatMessage(
            msg_id=_value(row, resolved.get("msg_id")) or f"{session_id}-{row_index}",
            sender=_value(row, resolved.get("sender")) or "",
            is_self=coerce_is_self(_raw(row, resolved.get("is_self"))),
            send_time=send_time_ms,
            msg_type=msg_type,
            content=_value(row, resolved.get("content")) or "",
        )

        if session_id not in sessions:
            sessions[session_id] = ChatSession(session_id=session_id, session_name=session_name, messages=[])
            session_order.append(session_id)
        sessions[session_id].messages.append(message)

    return ChatParseData(sessions=[sessions[sid] for sid in session_order], skipped=skipped)


def _has_any_value(row: Dict[str, Any]) -> bool:
    return any(v is not None and str(v).strip() != "" for v in row.values())


def _raw(row: Dict[str, Any], column: Optional[str]) -> Any:
    """Return the original cell value (preserving type, e.g. a native
    datetime.datetime from an xlsx date cell), or None if unmapped/blank."""
    if column is None:
        return None
    value = row.get(column)
    if isinstance(value, str) and not value.strip():
        return None
    return value


def _value(row: Dict[str, Any], column: Optional[str]) -> Optional[str]:
    """Like _raw, but stringified and stripped -- for text-shaped fields."""
    raw = _raw(row, column)
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None


def _read_rows(content: bytes, file_ext: str) -> List[Dict[str, Any]]:
    normalized_ext = (file_ext or "").strip().lower().lstrip(".")
    if normalized_ext == "csv":
        return _read_csv_rows(content)
    if normalized_ext == "xlsx":
        return _read_xlsx_rows(content)
    logger.error(
        "unsupported chat file_ext, errorCode=%s, fileExt=%s",
        ErrorCode.PARSE_FAILED,
        file_ext,
    )
    raise UnsupportedFormatError(f"unsupported chat file_ext '{file_ext}', supported: csv, xlsx")


def _read_csv_rows(content: bytes) -> List[Dict[str, Any]]:
    text = decode_bytes(content)
    reader = csv.DictReader(io.StringIO(text))
    return [dict(row) for row in reader]


def _read_xlsx_rows(content: bytes) -> List[Dict[str, Any]]:
    ensure_zip_is_safe(content)
    workbook = openpyxl.load_workbook(io.BytesIO(content), data_only=True, read_only=True)
    try:
        sheet = workbook.worksheets[0]
        rows_iter = sheet.iter_rows(values_only=True)
        try:
            header_row = next(rows_iter)
        except StopIteration:
            return []
        header = [str(cell) if cell is not None else "" for cell in header_row]
        rows: List[Dict[str, Any]] = []
        for row in rows_iter:
            rows.append({header[i]: row[i] for i in range(len(header)) if i < len(row)})
        return rows
    finally:
        workbook.close()
