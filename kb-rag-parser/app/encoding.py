"""Shared best-effort text decoding used by the txt/md and csv parsers.

Author: owlzhangfq@gmail.com
"""

# Encodings tried in order; utf-8-sig strips a BOM if present. gbk covers
# the common case of CSV/txt exported by Chinese-locale Excel/editors.
_CANDIDATE_ENCODINGS = ("utf-8-sig", "utf-8", "gbk")


def decode_bytes(content: bytes) -> str:
    """Decode bytes to text, trying common encodings before giving up.

    Never raises: the final fallback replaces undecodable bytes so a parser
    never fails an entire request over a handful of bad bytes.
    """
    for encoding in _CANDIDATE_ENCODINGS:
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("utf-8", errors="replace")
