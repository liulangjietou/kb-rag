"""Common parser interface implemented by every format-specific parser.

Author: owlzhangfq@gmail.com
"""

from abc import ABC, abstractmethod

from app.models import ParseData


class BaseParser(ABC):
    """Strategy interface: one implementation per supported file format.

    Implementations must be synchronous and CPU-bound only (no network
    I/O — see requirement doc §4.2 outbound-request ban). The API layer is
    responsible for running ``parse`` off the event loop and enforcing the
    overall timeout; parsers just do the actual extraction work.
    """

    @abstractmethod
    def parse(self, content: bytes, filename: str) -> ParseData:
        """Parse raw file bytes into the unified ParseData structure.

        Args:
            content: Raw bytes of the uploaded file.
            filename: Original filename, for diagnostics only.

        Returns:
            ParseData with markdown, per-page text, and images (always an
            empty list in M1 — see ParseData.images docstring).

        Raises:
            app.errors.ParseError (or a subclass) on any recoverable parse
            failure; other exceptions bubble up and are normalized to
            PARSE_FAILED by the API layer.
        """
        raise NotImplementedError
