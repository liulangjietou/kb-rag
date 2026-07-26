"""
Centralized constants for kb-rag-parser.

All magic numbers referenced by the security constraints in the M1 contract
(docs/M1-CONTRACTS.md §6) and the requirement doc (§4.2) live here so they are
defined exactly once and are easy to audit.

Author: owlzhangfq@gmail.com
"""

import os

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
PARSER_EXECUTOR_MAX_WORKERS = 4

# Supported file extensions (registry keys), kept in one place to avoid
# scattering the whitelist across modules.
SUPPORTED_FILE_EXTENSIONS = frozenset({"pdf", "docx", "txt", "md", "xlsx", "csv"})

# Zip-based formats that must go through the zip safety precheck before
# being handed to python-docx / openpyxl.
ZIP_BASED_FILE_EXTENSIONS = frozenset({"docx", "xlsx"})


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


# --- M3 multimodal parsing additions (M3-CONTRACTS.md §2.1) ---

# A page whose extracted text (whitespace-stripped) is shorter than this is
# treated as having no usable text layer ("scanned") and gets rendered to a
# PNG image instead of relying on (absent) text-layer extraction.
SCANNED_PAGE_TEXT_THRESHOLD = _read_int_env("SCANNED_PAGE_TEXT_THRESHOLD", 20)

# DPI used to render a scanned page to PNG. Fixed by the contract, not
# environment-configurable (only the threshold above is).
SCANNED_PAGE_RENDER_DPI = 150

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
SUPPORTED_CHAT_FILE_EXTENSIONS = frozenset({"csv", "xlsx"})
