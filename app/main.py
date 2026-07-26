"""
kb-rag-parser FastAPI application.

Endpoints:
  POST /api/v1/parse       multipart file + form file_ext -> ParseResponse
                           (M1-CONTRACTS.md §6, extended by M3-CONTRACTS.md §2.1
                           with images[]/warnings[] and per-page scanned)
  POST /api/v1/parse/chat  multipart file + form file_ext (+ mapping_profile)
                           -> ChatParseResponse (M3-CONTRACTS.md §2.2)
  GET  /health             -> {"status": "UP"}

Security constraints enforced here (requirement doc §4.2):
  - file size capped at MAX_FILE_SIZE_BYTES (100MB), fast-fail before parsing
  - zip-based formats (docx/xlsx) run the zip-slip / zip-bomb precheck
    inside their own parser (see app/security.py) before being opened
  - XML parsing hardened against XXE at process startup
  - a hard timeout (PARSE_TIMEOUT_SECONDS) bounds every parse call
  - no outbound network call is implemented anywhere in this service

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
    DEFAULT_CHAT_MAPPING_PROFILE,
    MAX_FILE_SIZE_BYTES,
    PARSE_TIMEOUT_SECONDS,
    PARSER_EXECUTOR_MAX_WORKERS,
    SERVICE_NAME,
)
from app.errors import ErrorCode, ParseError
from app.models import ChatParseData, ChatParseResponse, HealthResponse, ParseData, ParseResponse
from app.parsers.registry import get_parser
from app.security import ensure_file_size_within_limit, harden_xml_parsing

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)

# Applied once at import time, before any request can trigger XML parsing.
harden_xml_parsing()

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


def _parse_chat_blocking(content: bytes, file_ext: str, filename: str, mapping_profile: str) -> ChatParseData:
    ensure_file_size_within_limit(content, MAX_FILE_SIZE_BYTES)
    return parse_chat(content, filename, file_ext, mapping_profile)


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
    mapping_profile: str = Form(DEFAULT_CHAT_MAPPING_PROFILE),
) -> ChatParseResponse:
    """Parse a chat-log export (csv/xlsx) into sessions of messages.

    Same failure-normalization contract as /api/v1/parse: any recoverable
    error (unsupported file_ext, a mapping profile that can't resolve the
    required 'content' column, oversized file, zip-safety rejection,
    timeout) returns code=PARSE_FAILED instead of raising
    (M3-CONTRACTS.md §2.2).
    """
    request_id = uuid.uuid4().hex
    content = await file.read()

    code, data, message = await _run_blocking_parse(
        file_ext,
        lambda: _parse_chat_blocking(content, file_ext, file.filename or "", mapping_profile),
    )
    return ChatParseResponse(code=code, data=data, message=message, request_id=request_id)
