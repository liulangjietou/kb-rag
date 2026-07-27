"""
kb-rag-parser FastAPI application.

Endpoints:
  POST /api/v1/parse       multipart file + form file_ext -> ParseResponse
                           (M1-CONTRACTS.md §6, extended by M3-CONTRACTS.md §2.1
                           with images[]/warnings[] and per-page scanned; M8
                           adds pages[].ocr_source, M8-CONTRACTS.md §0.4)
  POST /api/v1/parse/chat  multipart file + form file_ext (csv/xlsx/txt/html)
                           + form mapping_profile/profile_yaml (both optional)
                           -> ChatParseResponse (M3-CONTRACTS.md §2.2, widened
                           by M8-CONTRACTS.md §0.1/§0.2/§0.7)
  GET  /health             -> {"status": "UP"}

Security constraints enforced here (requirement doc §4.2):
  - file size capped at MAX_FILE_SIZE_BYTES (100MB), fast-fail before parsing
  - zip-based formats (docx/xlsx) run the zip-slip / zip-bomb precheck
    inside their own parser (see app/security.py) before being opened
  - XML parsing hardened against XXE at process startup
  - html chat-log parsing (M8) strips script/style and never fetches a
    remote resource (see app/chat/html_adapter.py)
  - a hard timeout (PARSE_TIMEOUT_SECONDS) bounds every parse call
  - no outbound network call is implemented anywhere in this service
  - OCR_ENGINE=paddle (M8, optional, default "none") fast-fails at startup
    if the optional paddleocr dependency isn't installed (see
    app/ocr/engine.py)

Author: owlzhangfq@gmail.com
"""

import asyncio
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Optional, Tuple

from fastapi import FastAPI, File, Form, UploadFile

from app.chat.parser import parse_chat
from app.config import (
    DEFAULT_CHAT_MAPPING_PROFILE_BY_EXT,
    MAX_FILE_SIZE_BYTES,
    PARSE_TIMEOUT_SECONDS,
    PARSER_EXECUTOR_MAX_WORKERS,
    SERVICE_NAME,
)
from app.errors import ErrorCode, ParseError
from app.models import ChatParseData, ChatParseResponse, HealthResponse, ParseData, ParseResponse
from app.ocr.engine import ensure_ocr_engine_ready
from app.parsers.registry import get_parser
from app.security import ensure_file_size_within_limit, harden_xml_parsing

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)

# Applied once at import time, before any request can trigger XML parsing.
harden_xml_parsing()

# Fast-fail app startup (M8-CONTRACTS.md §0.4) when OCR_ENGINE=paddle is
# configured but the optional paddleocr dependency isn't installed --
# raises and lets the process crash rather than a confusing per-request
# failure the first time a scanned pdf page is encountered.
ensure_ocr_engine_ready()

app = FastAPI(title=SERVICE_NAME, version="0.1.0")

# Parsers are synchronous/CPU-bound; run them on a worker thread so the
# event loop stays free to serve /health and other concurrent requests.
_parser_executor = ThreadPoolExecutor(
    max_workers=PARSER_EXECUTOR_MAX_WORKERS, thread_name_prefix="parser-worker"
)


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness probe; always returns UP once the process is serving traffic."""
    return HealthResponse(status="UP")


async def _run_blocking_parse(file_ext: str, blocking_call: Callable[[], Any]) -> Tuple[str, Optional[Any], str]:
    """Run a blocking parse callable on the parser thread pool under the
    shared timeout, normalizing every recoverable failure mode to the same
    (code, data, message) triple used by both /api/v1/parse and
    /api/v1/parse/chat -- so the two endpoints don't duplicate this error
    handling (see app.errors.ParseError and its subclasses for what counts
    as "recoverable").
    """
    try:
        loop = asyncio.get_running_loop()
        data = await asyncio.wait_for(
            loop.run_in_executor(_parser_executor, blocking_call), timeout=PARSE_TIMEOUT_SECONDS
        )
        return ErrorCode.OK, data, "success"
    except asyncio.TimeoutError:
        logger.error(
            "parse timeout, errorCode=%s, fileExt=%s, timeoutSeconds=%d",
            ErrorCode.PARSE_FAILED,
            file_ext,
            PARSE_TIMEOUT_SECONDS,
        )
        return ErrorCode.PARSE_FAILED, None, f"parse timed out after {PARSE_TIMEOUT_SECONDS}s"
    except ParseError as exc:
        logger.error(
            "parse failed, errorCode=%s, fileExt=%s, reason=%s", ErrorCode.PARSE_FAILED, file_ext, exc
        )
        return ErrorCode.PARSE_FAILED, None, str(exc)
    except Exception as exc:  # last-resort guard: never leak a raw stack trace
        logger.error(
            "parse failed unexpectedly, errorCode=%s, fileExt=%s, reason=%s",
            ErrorCode.PARSE_FAILED,
            file_ext,
            exc,
        )
        return ErrorCode.PARSE_FAILED, None, f"unexpected parse error: {exc}"


def _parse_document_blocking(content: bytes, file_ext: str, filename: str) -> ParseData:
    ensure_file_size_within_limit(content, MAX_FILE_SIZE_BYTES)
    parser = get_parser(file_ext)
    return parser.parse(content, filename)


def _parse_chat_blocking(
    content: bytes,
    file_ext: str,
    filename: str,
    mapping_profile: Optional[str],
    profile_yaml: Optional[str],
) -> ChatParseData:
    ensure_file_size_within_limit(content, MAX_FILE_SIZE_BYTES)
    resolved_profile = mapping_profile or _default_mapping_profile_for(file_ext)
    return parse_chat(content, filename, file_ext, resolved_profile, profile_yaml)


def _default_mapping_profile_for(file_ext: str) -> str:
    """Built-in default mapping_profile per file_ext (M8-CONTRACTS.md
    §0.1/§0.2/§0.7) applied only when the caller doesn't pass
    mapping_profile at all -- csv/xlsx keep the original "memotrace"
    default; an unrecognized file_ext falls back to "memotrace" too, since
    it will fail fast in parse_chat()'s own file_ext whitelist check
    regardless of which profile name is attempted."""
    normalized_ext = (file_ext or "").strip().lower().lstrip(".")
    return DEFAULT_CHAT_MAPPING_PROFILE_BY_EXT.get(normalized_ext, "memotrace")


@app.post("/api/v1/parse", response_model=ParseResponse)
async def parse_document(
    file: UploadFile = File(...),
    file_ext: str = Form(...),
) -> ParseResponse:
    """Parse an uploaded document into markdown + per-page text + images.

    On any failure (unsupported format, security rejection, oversized file,
    timeout, or an unexpected error from an underlying library) this
    returns code=PARSE_FAILED with a descriptive message instead of raising,
    per the M1/M3 response contract.
    """
    request_id = uuid.uuid4().hex
    content = await file.read()

    code, data, message = await _run_blocking_parse(
        file_ext, lambda: _parse_document_blocking(content, file_ext, file.filename or "")
    )
    return ParseResponse(code=code, data=data, message=message, request_id=request_id)


@app.post("/api/v1/parse/chat", response_model=ChatParseResponse)
async def parse_chat_log(
    file: UploadFile = File(...),
    file_ext: str = Form(...),
    mapping_profile: Optional[str] = Form(None),
    profile_yaml: Optional[str] = Form(None),
) -> ChatParseResponse:
    """Parse a chat-log export (csv/xlsx/txt/html) into sessions of messages.

    file_ext widened to csv/xlsx/txt/html by M8-CONTRACTS.md §0.1/§0.2.
    mapping_profile, when omitted, resolves to a file_ext-appropriate
    built-in default (memotrace for csv/xlsx; liuhen_txt/liuhen_html for
    txt/html -- see app.config.DEFAULT_CHAT_MAPPING_PROFILE_BY_EXT).
    profile_yaml, when given, is the mapping profile's full YAML text and
    takes priority over mapping_profile resolving to a local
    app/mappings/*.yml file (M8-CONTRACTS.md §0.7) -- kb-rag-server passes
    this once mapping profiles are stored in t_kb_source_mapping rather
    than only as local files.

    Same failure-normalization contract as /api/v1/parse: any recoverable
    error (unsupported file_ext, a mapping profile that can't resolve the
    required 'content' column / txt line template / html selector,
    oversized file, zip-safety rejection, timeout) returns
    code=PARSE_FAILED instead of raising (M3-CONTRACTS.md §2.2).
    """
    request_id = uuid.uuid4().hex
    content = await file.read()

    code, data, message = await _run_blocking_parse(
        file_ext,
        lambda: _parse_chat_blocking(content, file_ext, file.filename or "", mapping_profile, profile_yaml),
    )
    return ChatParseResponse(code=code, data=data, message=message, request_id=request_id)
