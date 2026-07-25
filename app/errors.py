"""
Error codes and parser-specific exceptions.

Response error codes follow the M1 contract (docs/M1-CONTRACTS.md §5/§6):
success responses use code="OK"; any parse failure (unsupported format,
security rejection, oversized file, timeout, or an unexpected exception
raised by an underlying library) is normalized to code="PARSE_FAILED" with
a human-readable message. Codes are centralized here to avoid magic strings.

Author: owlzhangfq@gmail.com
"""


class ErrorCode:
    """String error codes used in the API response envelope."""

    OK = "OK"
    PARSE_FAILED = "PARSE_FAILED"


class ParseError(Exception):
    """Base class for all errors raised inside the parsing pipeline.

    Any subclass of this is caught at the API boundary and normalized to a
    PARSE_FAILED response instead of leaking a raw stack trace.
    """


class UnsupportedFormatError(ParseError):
    """Raised when file_ext is not present in the parser registry."""


class FileTooLargeError(ParseError):
    """Raised when the uploaded file exceeds MAX_FILE_SIZE_BYTES."""


class ZipSafetyError(ParseError):
    """Raised by the zip precheck: zip-slip path traversal, decompressed
    size bomb, or entry-count bomb detected in a docx/xlsx package."""
