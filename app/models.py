"""
Pydantic request/response models for kb-rag-parser.

The response envelope shape is fixed by docs/M1-CONTRACTS.md §5/§6:
success: {code:"OK", data:..., message, request_id}
failure: {code:"PARSE_FAILED", data:null, message, request_id}
"""

from typing import Any, List, Optional

from pydantic import BaseModel, Field

from app.errors import ErrorCode


class PageContent(BaseModel):
    """A single logical page of extracted plain text."""

    page_no: int = Field(..., description="1-based page number")
    text: str = Field(..., description="Extracted plain text of this page")


class ParseData(BaseModel):
    """Structured parse result returned on success."""

    markdown: str = Field(..., description="Full document content rendered as markdown")
    pages: List[PageContent] = Field(default_factory=list, description="Per-page text breakdown")
    images: List[Any] = Field(
        default_factory=list,
        description="Reserved for embedded image metadata; always empty in M1 "
        "(image extraction / VLM description is out of parser scope, see "
        "requirement doc §4.2 service boundary)",
    )


class ParseResponse(BaseModel):
    """Unified response envelope for POST /api/v1/parse."""

    code: str = Field(..., description="OK or PARSE_FAILED")
    data: Optional[ParseData] = None
    message: str = ""
    request_id: str


class HealthResponse(BaseModel):
    """Response body for GET /health."""

    status: str = "UP"
