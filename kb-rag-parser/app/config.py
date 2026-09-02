"""
Centralized constants for kb-rag-parser.

All magic numbers referenced by the security constraints in the M1 contract
(docs/M1-CONTRACTS.md §6) and the requirement doc (§4.2) live here so they are
defined exactly once and are easy to audit.

Author: owlzhangfq@gmail.com
"""

import os


def _read_int_env(name: str, default: int) -> int:
    """Read an integer env var, falling back to a fixed default on absence
    or a malformed value.

    Kept as the single lenient parsing point for runtime configuration
    (fast-fail is enforced elsewhere, at the request boundary) so a typo in
    an env var degrades to the documented default instead of crashing
    service startup.
    """
    raw = os.getenv(name)
    if not raw or not raw.strip():
        return default
    try:
        return int(raw.strip())
    except ValueError:
        return default


# Service identity
SERVICE_NAME = "kb-rag-parser"
SERVICE_PORT = 20001

# --- Upload / parse limits (requirement §4.2, contract §6) ---

# Single uploaded file size hard limit: 100MB.
MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024

# Zip safety precheck limits (defends docx/xlsx, which are zip+XML packages).
# Total uncompressed size across all zip entries must stay under 500MB.
MAX_ZIP_UNCOMPRESSED_TOTAL_BYTES = 500 * 1024 * 1024
# Number of entries inside the zip archive must stay under 2000.
MAX_ZIP_ENTRY_COUNT = 2000

# Hard timeout for a single parse invocation, in seconds.
PARSE_TIMEOUT_SECONDS = 300

# Thread pool size used to run blocking parser code off the event loop.
#
# This is the service's real concurrency ceiling: uvicorn runs a single process here, so however many
# documents kb-rag-server submits at once, only this many are parsed in parallel and the rest queue.
# Raising the caller's INDEX_CONCURRENCY past this value does not make anything faster - it only moves
# the queue upstream. Configurable (it used to be a fixed 4) because the right number depends on the
# host: unlike the caller's threads, these do real CPU work (PDF text extraction, page rendering, image
# decoding), so keep it under the core count and leave room for whatever else runs alongside
# (MySQL/ES/Qdrant, typically).
PARSER_EXECUTOR_MAX_WORKERS = _read_int_env("PARSER_MAX_WORKERS", 4)

# 支持格式的唯一事实源是 app/parsers/registry.py 的 _REGISTRY——get_parser() 直接以它查表分派，
# 这里不再另立一份影子白名单（曾有 SUPPORTED_FILE_EXTENSIONS / ZIP_BASED_FILE_EXTENSIONS 两个
# frozenset，零引用且已漂移：前者缺 html/htm，后者的意图由 docx/excel/chat 三个解析器各自调用
# ensure_zip_is_safe 实现，从不查表）。新增格式只需改 registry。


# --- M3 multimodal parsing additions (M3-CONTRACTS.md §2.1) ---

# A page whose extracted text (whitespace-stripped) is shorter than this is
# treated as having no usable text layer ("scanned") and gets rendered to a
# PNG image instead of relying on (absent) text-layer extraction.
SCANNED_PAGE_TEXT_THRESHOLD = _read_int_env("SCANNED_PAGE_TEXT_THRESHOLD", 20)

# DPI used to render a scanned page to PNG. Fixed by the contract, not
# environment-configurable (only the threshold above is).
SCANNED_PAGE_RENDER_DPI = 150

# A page whose text layer *exists* but decodes mostly to unrecognizable
# code points (typical of an embedded subset font with a missing/broken
# ToUnicode CMap: CJK content surfaces as e.g. Myanmar/box glyph soup) is
# treated as garbled. When fewer than this percentage of its
# non-whitespace characters fall in recognizable Unicode ranges
# (ASCII/CJK/kana/CJK punctuation, see app/parsers/pdf.py), the page
# falls back to the scanned-page path (page render + OCR/VLM) instead of
# indexing the glyph soup.
GARBLED_PAGE_VALID_CHAR_RATIO_PCT = _read_int_env("GARBLED_PAGE_VALID_CHAR_RATIO_PCT", 50)

# Per-document image-count cap and per-image byte-size cap. Exceeding
# either just skips that one image and records a warning (see
# app/parsers/images.py) instead of failing the whole document.
MAX_IMAGES_PER_DOC = _read_int_env("MAX_IMAGES_PER_DOC", 100)
MAX_IMAGE_BYTES = _read_int_env("MAX_IMAGE_BYTES", 10 * 1024 * 1024)

# Markdown placeholder token for an extracted/rendered image. Must occupy
# its own line so kb-rag-server can locate and replace it precisely.
IMAGE_PLACEHOLDER_TEMPLATE = "[[IMAGE:{image_id}]]"

# --- M3 chat log parsing additions (M3-CONTRACTS.md §2.2) ---

DEFAULT_CHAT_MAPPING_PROFILE = "memotrace"

# M8-CONTRACTS.md §0.1/§0.2: txt (line-template) and html (DOM) adapters
# widen the chat-log file_ext whitelist alongside the original csv/xlsx.
SUPPORTED_CHAT_FILE_EXTENSIONS = frozenset({"csv", "xlsx", "txt", "html"})

# Built-in mapping profile used per file_ext when the caller (kb-rag-server)
# does not pass mapping_profile at all -- csv/xlsx keep the original
# "memotrace" default unchanged; txt/html get their own built-in profiles
# (app/mappings/liuhen_txt.yml, app/mappings/liuhen_html.yml) since a
# column-name profile has no txt:/html: sections to fall back on.
DEFAULT_CHAT_MAPPING_PROFILE_BY_EXT = {
    "csv": DEFAULT_CHAT_MAPPING_PROFILE,
    "xlsx": DEFAULT_CHAT_MAPPING_PROFILE,
    "txt": "liuhen_txt",
    "html": "liuhen_html",
}

# --- M8 TXT line-template adapter additions (M8-CONTRACTS.md §0.1) ---

# A TXT export whose configured line templates match too few lines is
# almost certainly the wrong format/profile (e.g. a raw WeChat db dump
# fed in as-is) -- fail fast with an actionable error instead of silently
# emitting a near-empty session (M8-CONTRACTS.md §0.1).
TXT_UNMATCHED_LINE_RATIO_FAIL_THRESHOLD = 0.30

# --- M8 PaddleOCR fallback additions (M8-CONTRACTS.md §0.4) ---

OCR_ENGINE_NONE = "none"
OCR_ENGINE_PADDLE = "paddle"
_SUPPORTED_OCR_ENGINES = frozenset({OCR_ENGINE_NONE, OCR_ENGINE_PADDLE})

# Marks a PageContent as already OCR'd by this service, so kb-rag-server
# skips its own (VLM) OCR pass for that page (M8-CONTRACTS.md §0.4).
OCR_SOURCE_PADDLE = "paddle"

# ch_PP-OCRv4, the contract-mandated Chinese+English model.
OCR_MODEL_LANG = "ch"
OCR_MODEL_VERSION = "PP-OCRv4"


def _read_ocr_engine_env() -> str:
    """Same lenient-default philosophy as _read_int_env: an unset or
    unrecognized OCR_ENGINE value degrades to "none" (today's behavior)
    rather than crashing the process; only a *recognized but unsupported*
    engine value would ever need harder handling, and there is exactly one
    recognized alternative today ("paddle")."""
    raw = os.getenv("OCR_ENGINE")
    normalized = (raw or "").strip().lower()
    if normalized not in _SUPPORTED_OCR_ENGINES:
        return OCR_ENGINE_NONE
    return normalized


# Read once at import time like every other config constant here; per-call
# consumers (app.ocr.engine.get_ocr_engine) re-read the module attribute
# app.config.OCR_ENGINE rather than capturing this value in a closure, so
# tests can monkeypatch it per-case (same pattern as MAX_IMAGES_PER_DOC).
OCR_ENGINE = _read_ocr_engine_env()

# Per-page OCR wall-clock budget; on timeout the page is skipped (falls
# back to the pre-M8 behavior for that page) and counted, never fails the
# whole document (M8-CONTRACTS.md §0.4).
OCR_TIMEOUT_S = _read_int_env("OCR_TIMEOUT_S", 30)
