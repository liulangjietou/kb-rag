"""
Pydantic request/response models for kb-rag-parser.

The response envelope shape is fixed by docs/M1-CONTRACTS.md §5/§6:
success: {code:"OK", data:..., message, request_id}
failure: {code:"PARSE_FAILED", data:null, message, request_id}

M3 (docs/M3-CONTRACTS.md §2.1/§2.2) extends ParseData with images/warnings
and PageContent with scanned, and adds the chat-log models used by
POST /api/v1/parse/chat. Both extensions are additive; existing M1 fields
and their JSON shape are unchanged.

Author: owlzhangfq@gmail.com
"""

from typing import List, Optional

from pydantic import BaseModel, Field

from app.errors import ErrorCode


class PageContent(BaseModel):
    """A single logical page of extracted plain text."""

    page_no: int = Field(..., description="1-based page number")
    text: str = Field(..., description="Extracted plain text of this page")
    scanned: bool = Field(
        False,
        description="True when this page has no usable text layer (extracted "
        "text shorter than SCANNED_PAGE_TEXT_THRESHOLD) and was rendered to a "
        "page_render image instead (M3-CONTRACTS.md §2.1). Only pdf pages can "
        "ever be scanned; every other format always reports False.",
    )


class ImageAsset(BaseModel):
    """One extracted or rendered image, referenced by a markdown placeholder.

    kind="embedded": an inline raster image found in the source pdf/docx.
    kind="page_render": a whole scanned pdf page rendered to PNG so
    kb-rag-server's VLM can OCR it (M3-CONTRACTS.md §2.1).
    """

    image_id: str = Field(..., description="Matches the [[IMAGE:{image_id}]] placeholder in markdown")
    page_no: int = Field(..., description="1-based page this image belongs to")
    kind: str = Field(..., description="'embedded' or 'page_render'")
    media_type: str = Field(..., description="MIME type, e.g. image/png")
    content_base64: str = Field(..., description="Base64-encoded raw image bytes")


class ParseData(BaseModel):
    """Structured parse result returned on success."""

    markdown: str = Field(
        ...,
        description="Full document content rendered as markdown; may contain "
        "[[IMAGE:{image_id}]] placeholder lines (M3-CONTRACTS.md §2.1)",
    )
    pages: List[PageContent] = Field(default_factory=list, description="Per-page text breakdown")
    images: List[ImageAsset] = Field(
        default_factory=list,
        description="Embedded and page-rendered images extracted from the "
        "document (M3-CONTRACTS.md §2.1); always empty for formats with no "
        "image concept (txt/md/csv) or when a document has none",
    )
    warnings: List[str] = Field(
        default_factory=list,
        description="Non-fatal issues encountered while parsing, e.g. an "
        "image skipped for exceeding the per-doc count or per-image byte "
        "size cap (M3-CONTRACTS.md §2.1); never causes the whole parse to fail",
    )


class ParseResponse(BaseModel):
    """Unified response envelope for POST /api/v1/parse."""

    code: str = Field(..., description="OK or PARSE_FAILED")
    data: Optional[ParseData] = None
    message: str = ""
    request_id: str


class ChatMessage(BaseModel):
    """One normalized chat message (M3-CONTRACTS.md §2.2)."""

    msg_id: str
    sender: str
    is_self: bool
    send_time: int = Field(..., description="Epoch milliseconds")
    msg_type: str = Field(
        ..., description="text|image|other -- voice/video messages are filtered out before reaching this list"
    )
    content: str


class ChatSession(BaseModel):
    """One chat session (room/contact) and its ordered messages."""

    session_id: str
    session_name: str
    messages: List[ChatMessage] = Field(default_factory=list)


class ChatSkippedStats(BaseModel):
    """Counts of messages excluded from the sessions[] output, by reason."""

    voice: int = 0
    video: int = 0
    other: int = Field(0, description="e.g. a row whose send_time could not be parsed in any known format")


class ChatParseData(BaseModel):
    """Structured chat parse result returned on success."""

    sessions: List[ChatSession] = Field(default_factory=list)
    skipped: ChatSkippedStats = Field(default_factory=ChatSkippedStats)


class ChatParseResponse(BaseModel):
    """Unified response envelope for POST /api/v1/parse/chat."""

    code: str = Field(..., description="OK or PARSE_FAILED")
    data: Optional[ChatParseData] = None
    message: str = ""
    request_id: str


class HealthResponse(BaseModel):
    """Response body for GET /health."""

    status: str = "UP"
