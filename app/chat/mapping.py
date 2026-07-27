"""Mapping profile loader for chat log parsing.

A mapping profile is a YAML document -- normally a file under
app/mappings/*.yml, or (M8-CONTRACTS.md §0.7) the full YAML text passed by
the caller as ``profile_yaml``, which then takes priority over any local
file of the same name. It carries three independent concerns, one per
source shape:

- csv/xlsx: for each logical target field, an ordered list of candidate
  source column names (M3-CONTRACTS.md §2.2, ``TARGET_FIELDS``/``resolve``).
  Column matching is case-insensitive and ignores whitespace, so exports
  with slightly different header casing/spacing still resolve without
  touching any code.
- txt: an ordered list of line-header regex templates under the ``txt:``
  section (M8-CONTRACTS.md §0.1, ``txt_patterns``).
- html: a set of DOM node selectors under the ``html:`` section
  (M8-CONTRACTS.md §0.2, ``html_selectors``).

Onboarding a brand-new export source is therefore "drop/pass a yml
document", never "change parser code".

Author: owlzhangfq@gmail.com
"""

import logging
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

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

# A txt: pattern must capture these two named groups at minimum; "content"
# is optional (present only for line templates whose message body can
# start on the same line as the header, e.g. the built-in wechat_pc
# template -- see app/chat/txt_adapter.py).
_TXT_REQUIRED_GROUPS = ("send_time", "sender")


def _normalize_header(name: str) -> str:
    """Case/space-insensitive key used purely for header matching."""
    return "".join(str(name).split()).lower()


class TxtLinePattern:
    """One compiled txt: line-header template (M8-CONTRACTS.md §0.1)."""

    def __init__(self, name: str, compiled: "re.Pattern[str]") -> None:
        self.name = name
        self.compiled = compiled


class MappingProfile:
    """Resolves a chat-log mapping profile: csv/xlsx column candidates,
    txt line-header patterns, and html DOM selectors."""

    def __init__(
        self,
        name: str,
        candidates: Dict[str, List[str]],
        txt_patterns: List[TxtLinePattern],
        html_selectors: Dict[str, str],
    ) -> None:
        self.name = name
        self._candidates = candidates
        self.txt_patterns = txt_patterns
        self.html_selectors = html_selectors

    @classmethod
    def load(cls, profile_name: str, profile_yaml: Optional[str] = None) -> "MappingProfile":
        """Load a mapping profile, or raise ChatMappingError.

        profile_yaml, when non-blank, is the profile's full YAML text
        passed by the caller (kb-rag-server) and takes priority over any
        local app/mappings/{profile_name}.yml (M8-CONTRACTS.md §0.7) --
        local files remain in place purely as seed/default content, never
        consulted once profile_yaml is supplied. profile_name is still used
        as this profile's display name for logging either way.
        """
        if profile_yaml is not None and profile_yaml.strip():
            try:
                raw = yaml.safe_load(profile_yaml) or {}
            except yaml.YAMLError as exc:
                logger.error(
                    "chat mapping profile_yaml invalid, errorCode=%s, profile=%s",
                    ErrorCode.PARSE_FAILED,
                    profile_name,
                )
                raise ChatMappingError(f"profile_yaml for '{profile_name}' is not valid yaml: {exc}") from exc
            return cls._from_raw(profile_name or "inline", raw)

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
        return cls._from_raw(profile_name, raw)

    @classmethod
    def _from_raw(cls, name: str, raw: Dict[str, Any]) -> "MappingProfile":
        candidates = {field: list(raw.get(field) or []) for field in TARGET_FIELDS}
        txt_patterns = cls._parse_txt_patterns(name, raw.get("txt"))
        html_selectors = cls._parse_html_selectors(raw.get("html"))
        return cls(name, candidates, txt_patterns, html_selectors)

    @staticmethod
    def _parse_txt_patterns(profile_name: str, txt_section: Optional[Dict[str, Any]]) -> List[TxtLinePattern]:
        """Compile the txt: section's ``patterns:`` list eagerly (a regex
        typo is a profile-loading concern, so it fails at load time rather
        than on the first line of the first request that hits it)."""
        if not txt_section:
            return []
        entries = txt_section.get("patterns") or []
        patterns: List[TxtLinePattern] = []
        for entry in entries:
            pattern_name = str(entry.get("name") or f"pattern_{len(patterns) + 1}")
            regex_text = entry.get("regex")
            if not regex_text:
                raise ChatMappingError(
                    f"mapping profile '{profile_name}' txt: pattern '{pattern_name}' is missing 'regex'"
                )
            try:
                compiled = re.compile(regex_text)
            except re.error as exc:
                raise ChatMappingError(
                    f"mapping profile '{profile_name}' txt: pattern '{pattern_name}' has invalid regex: {exc}"
                ) from exc
            missing_groups = [g for g in _TXT_REQUIRED_GROUPS if g not in compiled.groupindex]
            if missing_groups:
                raise ChatMappingError(
                    f"mapping profile '{profile_name}' txt: pattern '{pattern_name}' is missing "
                    f"required named group(s) {missing_groups}"
                )
            patterns.append(TxtLinePattern(pattern_name, compiled))
        return patterns

    @staticmethod
    def _parse_html_selectors(html_section: Optional[Dict[str, Any]]) -> Dict[str, str]:
        if not html_section:
            return {}
        return {str(key): str(value) for key, value in html_section.items() if value}

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
