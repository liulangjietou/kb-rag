"""Negative tests for the zip safety precheck (requirement doc §4.2).

Covers zip-slip (path traversal) and zip-bomb (entry-count / total
uncompressed size) rejection, both at the unit level (app.security) and
end-to-end through POST /api/v1/parse with file_ext="docx" (docx is a zip
package, so the endpoint must reject a malicious zip before ever handing it
to python-docx).
"""

import io
import zipfile

import pytest

from app.config import MAX_ZIP_ENTRY_COUNT
from app.errors import ZipSafetyError
from app.security import ensure_zip_is_safe
from tests.conftest import post_parse


def _build_zip(entries: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


# --- Unit-level: app.security.ensure_zip_is_safe ---------------------------


def test_ensure_zip_is_safe_rejects_path_traversal_entry():
    malicious_zip = _build_zip({"../../etc/evil.txt": b"pwned"})

    with pytest.raises(ZipSafetyError, match="escapes"):
        ensure_zip_is_safe(malicious_zip)


def test_ensure_zip_is_safe_rejects_absolute_path_entry():
    malicious_zip = _build_zip({"/etc/evil.txt": b"pwned"})

    with pytest.raises(ZipSafetyError, match="escapes"):
        ensure_zip_is_safe(malicious_zip)


def test_ensure_zip_is_safe_rejects_total_size_bomb():
    # Highly compressible payload: small on the wire, huge once decompressed.
    bomb_entry = _build_zip({"word/document.xml": b"A" * 10_000})

    with pytest.raises(ZipSafetyError, match="uncompressed size"):
        ensure_zip_is_safe(bomb_entry, max_total_uncompressed_bytes=1_000)


def test_ensure_zip_is_safe_rejects_entry_count_bomb():
    many_entries = {f"part-{i}.xml": b"x" for i in range(10)}
    bomb_entry = _build_zip(many_entries)

    with pytest.raises(ZipSafetyError, match="entry count"):
        ensure_zip_is_safe(bomb_entry, max_entry_count=5)


def test_ensure_zip_is_safe_accepts_a_well_formed_archive():
    ok_zip = _build_zip({"word/document.xml": b"<root/>"})

    ensure_zip_is_safe(ok_zip)  # must not raise


# --- End-to-end: POST /api/v1/parse with file_ext="docx" -------------------


def test_parse_rejects_zip_slip_docx(client):
    malicious_zip = _build_zip({"../../etc/evil.txt": b"pwned"})

    response = post_parse(client, "evil.docx", malicious_zip, "docx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
    assert "escapes" in body["message"]


def test_parse_rejects_zip_entry_count_bomb_docx(client):
    # Exceeds the real MAX_ZIP_ENTRY_COUNT limit wired into the endpoint.
    many_entries = {f"part-{i}.xml": b"x" for i in range(MAX_ZIP_ENTRY_COUNT + 1)}
    bomb_zip = _build_zip(many_entries)

    response = post_parse(client, "bomb.docx", bomb_zip, "docx")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "PARSE_FAILED"
    assert body["data"] is None
    assert "entry count" in body["message"]
