"""Chat log parsing: turns a csv/xlsx/txt/html chat export into sessions of
messages (M3-CONTRACTS.md §2.2, widened by M8-CONTRACTS.md §0.1/§0.2/§0.3).

csv/xlsx rows are read generically -- csv.DictReader / openpyxl worksheet
rows -- into a list of {actual_header_name: raw_value} dicts, independent
of which export tool produced the file; a MappingProfile then resolves
each logical field (session_id, sender, send_time, ...) to whichever
actual header name is present. txt/html have no column-naming ambiguity
to resolve (their own adapters -- app.chat.txt_adapter /
app.chat.html_adapter -- already produce exactly the normalized fields
from a line-header regex / DOM selector, both also carried by the mapping
profile's txt:/html: sections), so they bypass MappingProfile.resolve()
entirely and are always modeled as a single ChatSession (a txt/html export
is one conversation dump, not a multi-room table). Onboarding a new
tabular export source is still a matter of adding a mappings/*.yml file
with candidate column names, never touching this module.

Author: owlzhangfq@gmail.com
"""

import csv
import io
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

import openpyxl

from app.chat.html_adapter import parse_html_messages
from app.chat.mapping import MappingProfile
from app.chat.normalize import classify_msg_type, coerce_is_self, parse_send_time_ms
from app.chat.txt_adapter import parse_txt_messages
from app.config import SUPPORTED_CHAT_FILE_EXTENSIONS
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


def parse_chat(
    content: bytes,
    filename: str,
    file_ext: str,
    mapping_profile: str,
    profile_yaml: Optional[str] = None,
) -> ChatParseData:
    """Parse chat log bytes into ChatParseData, or raise a ParseError subclass.

    Dispatches on file_ext: csv/xlsx go through the existing column-mapping
    pipeline unchanged; txt/html go through their own line-template/DOM
    adapters (M8-CONTRACTS.md §0.1/§0.2). profile_yaml, when given, is the
    mapping profile's full YAML text and takes priority over mapping_profile
    resolving to a local app/mappings/*.yml file (M8-CONTRACTS.md §0.7).
    """
    normalized_ext = (file_ext or "").strip().lower().lstrip(".")
    if normalized_ext not in SUPPORTED_CHAT_FILE_EXTENSIONS:
        logger.error(
            "unsupported chat file_ext, errorCode=%s, fileExt=%s",
            ErrorCode.PARSE_FAILED,
            file_ext,
        )
        raise UnsupportedFormatError(
            f"unsupported chat file_ext '{file_ext}', supported: {sorted(SUPPORTED_CHAT_FILE_EXTENSIONS)}"
        )

    if normalized_ext == "txt":
        return _parse_txt_chat(content, filename, mapping_profile, profile_yaml)
    if normalized_ext == "html":
        return _parse_html_chat(content, filename, mapping_profile, profile_yaml)
    return _parse_tabular_chat(content, normalized_ext, mapping_profile, profile_yaml)


def _parse_txt_chat(
    content: bytes, filename: str, mapping_profile: str, profile_yaml: Optional[str]
) -> ChatParseData:
    """A TXT export is one conversation dump -- always a single session,
    named after the file (falling back to the generic default id/name)."""
    text = decode_bytes(content)
    profile = MappingProfile.load(mapping_profile, profile_yaml)
    session_id = _session_id_from_filename(filename)
    messages, skipped_other = parse_txt_messages(text, profile.txt_patterns, session_id)
    skipped = ChatSkippedStats(other=skipped_other)
    if not messages:
        return ChatParseData(sessions=[], skipped=skipped)
    session = ChatSession(session_id=session_id, session_name=session_id, messages=messages)
    return ChatParseData(sessions=[session], skipped=skipped)


def _parse_html_chat(
    content: bytes, filename: str, mapping_profile: str, profile_yaml: Optional[str]
) -> ChatParseData:
    """Same single-session modeling as TXT (see _parse_txt_chat)."""
    html_text = decode_bytes(content)
    profile = MappingProfile.load(mapping_profile, profile_yaml)
    session_id = _session_id_from_filename(filename)
    messages, skipped = parse_html_messages(html_text, profile.html_selectors, session_id)
    if not messages:
        return ChatParseData(sessions=[], skipped=skipped)
    session = ChatSession(session_id=session_id, session_name=session_id, messages=messages)
    return ChatParseData(sessions=[session], skipped=skipped)


def _session_id_from_filename(filename: str) -> str:
    stem = Path(filename).stem.strip() if filename else ""
    return stem or _DEFAULT_SESSION_ID


def _parse_tabular_chat(
    content: bytes, file_ext: str, mapping_profile: str, profile_yaml: Optional[str]
) -> ChatParseData:
    """csv/xlsx path -- unchanged from the pre-M8 M3 implementation, aside
    from MappingProfile.load() now accepting an optional profile_yaml
    override (M8-CONTRACTS.md §0.7)."""
    rows = _read_rows(content, file_ext)
    if not rows:
        return ChatParseData(sessions=[], skipped=ChatSkippedStats())

    profile = MappingProfile.load(mapping_profile, profile_yaml)
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
    """file_ext is already validated (normalized to csv/xlsx) by the
    parse_chat() dispatch above -- the fast-fail single point for an
    unsupported chat file_ext lives there, not duplicated here."""
    if file_ext == "csv":
        return _read_csv_rows(content)
    return _read_xlsx_rows(content)


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
