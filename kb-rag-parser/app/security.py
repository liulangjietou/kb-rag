"""
Parsing security guardrails (requirement doc §4.2 / M1-CONTRACTS.md §6).

Three constraints are enforced here:

1. Upload size cap (100MB) — ``ensure_file_size_within_limit``.
2. Zip safety precheck for zip-based office formats (docx/xlsx) — guards
   against zip-slip path traversal and zip-bomb (decompressed size / entry
   count) attacks before the bytes are ever handed to python-docx or
   openpyxl — ``ensure_zip_is_safe``.
3. XXE hardening for XML parsing — ``harden_xml_parsing``.

Regarding (3): docx/xlsx are zip+XML packages. python-docx and openpyxl both
parse their internal XML with lxml (audited against the installed versions
pinned in requirements.txt: docx/oxml/parser.py and docx/opc/oxml.py build
their ``etree.XMLParser`` with ``resolve_entities=False``; openpyxl's
``openpyxl/xml/functions.py`` does the same via its ``safe_parser`` when
lxml is present, and falls back to ``defusedxml.ElementTree`` when lxml is
absent). ``resolve_entities=False`` is what actually blocks XXE (entity
references are left unexpanded instead of being resolved against a local or
remote resource), so no monkeypatching of those libraries is required as
long as their pinned versions keep that default. As defense-in-depth for any
other stdlib XML usage in the process (current or future parsers),
``harden_xml_parsing`` still calls ``defusedxml.defuse_stdlib()`` once at
startup to globally patch ``xml.etree`` / ``xml.dom.minidom`` / ``xml.sax``.
No outbound network request is implemented anywhere in this service, which
also removes the classic XXE-to-SSRF exfiltration path even if an external
entity reference were ever resolved.

Author: owlzhangfq@gmail.com
"""

import io
import logging
import posixpath
import zipfile
from typing import Final

from app.config import MAX_ZIP_ENTRY_COUNT, MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES
from app.errors import ErrorCode, FileTooLargeError, ZipSafetyError

logger = logging.getLogger(__name__)

_PATH_TRAVERSAL_PREFIX: Final = ".."


def ensure_file_size_within_limit(content: bytes, max_size_bytes: int) -> None:
    """Fast-fail if the uploaded file exceeds the configured size cap."""
    size = len(content)
    if size > max_size_bytes:
        logger.error(
            "upload rejected, errorCode=%s, reason=file_too_large, sizeBytes=%d, limitBytes=%d",
            ErrorCode.PARSE_FAILED,
            size,
            max_size_bytes,
        )
        raise FileTooLargeError(
            f"file size {size} bytes exceeds limit {max_size_bytes} bytes"
        )


def _is_path_escaping(entry_name: str) -> bool:
    """Detect zip-slip: absolute paths or entries that normalize outside root."""
    if not entry_name:
        return False
    if entry_name.startswith("/") or entry_name.startswith("\\"):
        return True
    # Windows drive-letter absolute path, e.g. "C:/evil".
    if len(entry_name) >= 2 and entry_name[1] == ":":
        return True
    normalized = posixpath.normpath(entry_name.replace("\\", "/"))
    return normalized == _PATH_TRAVERSAL_PREFIX or normalized.startswith(
        _PATH_TRAVERSAL_PREFIX + "/"
    )


def ensure_zip_is_safe(
    content: bytes,
    max_total_uncompressed_bytes: int = MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES,
    max_entry_count: int = MAX_ZIP_ENTRY_COUNT,
) -> None:
    """Precheck a zip-based office document (docx/xlsx) before parsing it.

    Rejects the archive fast if any entry path escapes the archive root
    (zip-slip), or if the total uncompressed size / entry count exceeds the
    configured bomb-protection limits, without ever extracting to disk.
    """
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            infos = archive.infolist()
            entry_count = len(infos)
            if entry_count > max_entry_count:
                logger.error(
                    "zip rejected, errorCode=%s, reason=entry_count_bomb, entryCount=%d, limit=%d",
                    ErrorCode.PARSE_FAILED,
                    entry_count,
                    max_entry_count,
                )
                raise ZipSafetyError(
                    f"zip entry count {entry_count} exceeds limit {max_entry_count}"
                )

            total_uncompressed = 0
            for info in infos:
                if _is_path_escaping(info.filename):
                    logger.error(
                        "zip rejected, errorCode=%s, reason=zip_slip, entryName=%s",
                        ErrorCode.PARSE_FAILED,
                        info.filename,
                    )
                    raise ZipSafetyError(
                        f"zip entry '{info.filename}' escapes the archive root"
                    )
                total_uncompressed += info.file_size
                if total_uncompressed > max_total_uncompressed_bytes:
                    logger.error(
                        "zip rejected, errorCode=%s, reason=size_bomb, "
                        "uncompressedBytes=%d, limitBytes=%d",
                        ErrorCode.PARSE_FAILED,
                        total_uncompressed,
                        max_total_uncompressed_bytes,
                    )
                    raise ZipSafetyError(
                        f"zip uncompressed size {total_uncompressed} bytes "
                        f"exceeds limit {max_total_uncompressed_bytes} bytes"
                    )
    except zipfile.BadZipFile as exc:
        logger.error("zip rejected, errorCode=%s, reason=bad_zip_file", ErrorCode.PARSE_FAILED)
        raise ZipSafetyError(f"not a valid zip package: {exc}") from exc


def harden_xml_parsing() -> None:
    """Globally patch stdlib XML modules to reject DTDs/external entities.

    Called once at process startup (see app/main.py). Defense-in-depth on
    top of the per-library audit documented in this module's docstring.
    """
    import defusedxml

    defusedxml.defuse_stdlib()
    logger.info("xml parsing hardened via defusedxml.defuse_stdlib")
