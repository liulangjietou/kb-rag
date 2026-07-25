"""
Centralized constants for kb-rag-parser.

All magic numbers referenced by the security constraints in the M1 contract
(docs/M1-CONTRACTS.md §6) and the requirement doc (§4.2) live here so they are
defined exactly once and are easy to audit.
"""

# Service identity
SERVICE_NAME = "kb-rag-parser"
SERVICE_PORT = 8001

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
