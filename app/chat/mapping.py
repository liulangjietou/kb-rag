"""Column-name mapping profile loader for chat log parsing.

A mapping profile is a YAML file under app/mappings/*.yml naming, for each
logical target field, an ordered list of candidate source column names
(M3-CONTRACTS.md §2.2). Column matching is case-insensitive and ignores
whitespace, so exports with slightly different header casing/spacing still
resolve without touching any code -- onboarding a brand-new export source
is "drop a yml file", never "change parser code".

Author: owlzhangfq@gmail.com
"""

import logging
from pathlib import Path
from typing import Dict, List, Optional

import yaml

from app.errors import ChatMappingError, ErrorCode

logger = logging.getLogger(__name__)

# app/mappings/, resolved relative to this package so it is found
# regardless of the process's current working directory.
_MAPPINGS_DIR = Path(__file__).resolve().parent.parent / "mappings"

# Logical target fields a mapping profile may define candidates for. Only
# "content" is a hard requirement at parse time (see app.chat.parser); the
# others fall back gracefully when unresolved.
TARGET_FIELDS = (
    "session_id",
    "session_name",
    "sender",
    "is_self",
    "send_time",
    "msg_type",
    "content",
    "msg_id",
)


def _normalize_header(name: str) -> str:
    """Case/space-insensitive key used purely for header matching."""
    return "".join(str(name).split()).lower()


class MappingProfile:
    """Resolves logical target field names to actual header column names."""

    def __init__(self, name: str, candidates: Dict[str, List[str]]) -> None:
        self.name = name
        self._candidates = candidates

    @classmethod
    def load(cls, profile_name: str) -> "MappingProfile":
        """Load app/mappings/{profile_name}.yml, or raise ChatMappingError."""
        path = _MAPPINGS_DIR / f"{profile_name}.yml"
        if not path.is_file():
            logger.error(
                "chat mapping profile not found, errorCode=%s, profile=%s, path=%s",
                ErrorCode.PARSE_FAILED,
                profile_name,
                path,
            )
            raise ChatMappingError(f"unknown mapping_profile '{profile_name}'")

        with path.open("r", encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}
        candidates = {field: list(raw.get(field) or []) for field in TARGET_FIELDS}
        return cls(profile_name, candidates)

    def resolve(self, header: List[str]) -> Dict[str, Optional[str]]:
        """Map each target field to the first matching actual header name.

        header: the actual column names present in the uploaded file (csv
        header row / xlsx first row). Returns a dict with every entry in
        TARGET_FIELDS, value None where no candidate matched.
        """
        normalized_to_actual = {_normalize_header(h): h for h in header}
        resolved: Dict[str, Optional[str]] = {}
        for field, candidate_names in self._candidates.items():
            match = None
            for candidate in candidate_names:
                actual = normalized_to_actual.get(_normalize_header(candidate))
                if actual is not None:
                    match = actual
                    break
            resolved[field] = match
        return resolved
