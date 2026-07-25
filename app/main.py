"""
kb-rag-parser FastAPI application.

Endpoints (M1-CONTRACTS.md §6):
  POST /api/v1/parse  multipart file + form file_ext -> ParseResponse
  GET  /health        -> {"status": "UP"}

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

from fastapi import FastAPI, File, Form, UploadFile

from app.config import (
    MAX_FILE_SIZE_BYTES,
    PARSE_TIMEOUT_SECONDS,
    PARSER_EXECUTOR_MAX_WORKERS,
    SERVICE_NAME,
)
from app.errors import ErrorCode, ParseError
from app.models import HealthResponse, ParseResponse
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


@app.post("/api/v1/parse", response_model=ParseResponse)
async def parse_document(
    file: UploadFile = File(...),
    file_ext: str = Form(...),
) -> ParseResponse:
    """Parse an uploaded document into markdown + per-page text.

    On any failure (unsupported format, security rejection, oversized file,
    timeout, or an unexpected error from an underlying library) this
    returns code=PARSE_FAILED with a descriptive message instead of raising,
    per the M1 response contract.
    """
    request_id = uuid.uuid4().hex
    content = await file.read()

    try:
        ensure_file_size_within_limit(content, MAX_FILE_SIZE_BYTES)
        parser = get_parser(file_ext)

        loop = asyncio.get_running_loop()
        data = await asyncio.wait_for(
            loop.run_in_executor(_parser_executor, parser.parse, content, file.filename or ""),
            timeout=PARSE_TIMEOUT_SECONDS,
        )
        return ParseResponse(code=ErrorCode.OK, data=data, message="success", request_id=request_id)

    except asyncio.TimeoutError:
        logger.error(
            "parse document timeout, errorCode=%s, fileExt=%s, timeoutSeconds=%d",
            ErrorCode.PARSE_FAILED,
            file_ext,
            PARSE_TIMEOUT_SECONDS,
        )
        return ParseResponse(
            code=ErrorCode.PARSE_FAILED,
            data=None,
            message=f"parse timed out after {PARSE_TIMEOUT_SECONDS}s",
            request_id=request_id,
        )
    except ParseError as exc:
        logger.error(
            "parse document failed, errorCode=%s, fileExt=%s, reason=%s",
            ErrorCode.PARSE_FAILED,
            file_ext,
            exc,
        )
        return ParseResponse(
            code=ErrorCode.PARSE_FAILED, data=None, message=str(exc), request_id=request_id
        )
    except Exception as exc:  # last-resort guard: never leak a raw stack trace
        logger.error(
            "parse document failed unexpectedly, errorCode=%s, fileExt=%s, reason=%s",
            ErrorCode.PARSE_FAILED,
            file_ext,
            exc,
        )
        return ParseResponse(
            code=ErrorCode.PARSE_FAILED,
            data=None,
            message=f"unexpected parse error: {exc}",
            request_id=request_id,
        )
