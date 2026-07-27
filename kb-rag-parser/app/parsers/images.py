"""Shared image-asset collection used by parsers that extract embedded or
page-rendered images (pdf, docx) -- see docs/M3-CONTRACTS.md §2.1.

Centralizing the per-document image-count cap and per-image byte-size cap
here means every parser enforces the same limits the same way instead of
duplicating guard logic, and keeps image_id assignment sequential and
consistent regardless of kind (embedded / page_render).

Author: owlzhangfq@gmail.com
"""

import base64
import logging
from typing import List, Optional

from app import config
from app.config import IMAGE_PLACEHOLDER_TEMPLATE
from app.models import ImageAsset

logger = logging.getLogger(__name__)

# Maps a lowercase file extension (no dot), as returned by pymupdf's
# extract_image()/ a zip media entry name, to its MIME type. Extend here if
# a new embedded image codec needs to be supported.
_EXT_TO_MEDIA_TYPE = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "gif": "image/gif",
    "bmp": "image/bmp",
    "tif": "image/tiff",
    "tiff": "image/tiff",
    "webp": "image/webp",
}

_DEFAULT_MEDIA_TYPE = "application/octet-stream"

# image "kind" values shared with app.models.ImageAsset.kind; centralized
# here so pdf.py/docx.py never spell them out as inline string literals.
KIND_EMBEDDED = "embedded"
KIND_PAGE_RENDER = "page_render"


def guess_media_type(ext: str) -> str:
    """Map a raw file extension to a MIME type, defaulting to a generic binary type."""
    return _EXT_TO_MEDIA_TYPE.get(ext.lower().lstrip("."), _DEFAULT_MEDIA_TYPE)


def image_placeholder(image_id: str) -> str:
    """Render the fixed `[[IMAGE:{image_id}]]` markdown placeholder token."""
    return IMAGE_PLACEHOLDER_TEMPLATE.format(image_id=image_id)


class ImageAssetCollector:
    """Accumulates ImageAsset entries for one document, enforcing caps.

    One instance is created per parse() call and discarded afterwards; not
    shared across documents or threads.
    """

    def __init__(
        self,
        max_images: Optional[int] = None,
        max_image_bytes: Optional[int] = None,
    ) -> None:
        # Resolved from app.config at construction time (one instance per
        # parse() call) rather than bound as a function-default at import
        # time, so tests can monkeypatch app.config.MAX_IMAGES_PER_DOC /
        # MAX_IMAGE_BYTES and have it actually take effect per-request.
        self._max_images = config.MAX_IMAGES_PER_DOC if max_images is None else max_images
        self._max_image_bytes = config.MAX_IMAGE_BYTES if max_image_bytes is None else max_image_bytes
        self._images: List[ImageAsset] = []
        self._warnings: List[str] = []
        self._next_seq = 1

    @property
    def images(self) -> List[ImageAsset]:
        return self._images

    @property
    def warnings(self) -> List[str]:
        return self._warnings

    def try_add(self, page_no: int, kind: str, media_type: str, raw_bytes: bytes) -> Optional[str]:
        """Add one image if under both caps; returns its image_id, or None if skipped.

        A skip never raises: the containing document remains fully
        parseable (M3-CONTRACTS.md §2.1 "超限跳过并写 warnings，不失败整篇").
        """
        if len(self._images) >= self._max_images:
            logger.info(
                "image skipped, reason=doc_image_count_limit, pageNo=%d, kind=%s, limit=%d",
                page_no,
                kind,
                self._max_images,
            )
            self._warnings.append(
                f"image on page {page_no} ({kind}) skipped: document image count reached limit {self._max_images}"
            )
            return None

        size = len(raw_bytes)
        if size > self._max_image_bytes:
            logger.info(
                "image skipped, reason=image_bytes_limit, pageNo=%d, kind=%s, sizeBytes=%d, limitBytes=%d",
                page_no,
                kind,
                size,
                self._max_image_bytes,
            )
            self._warnings.append(
                f"image on page {page_no} ({kind}) skipped: size {size} bytes exceeds limit {self._max_image_bytes} bytes"
            )
            return None

        image_id = f"img_{self._next_seq}"
        self._next_seq += 1
        self._images.append(
            ImageAsset(
                image_id=image_id,
                page_no=page_no,
                kind=kind,
                media_type=media_type,
                content_base64=base64.b64encode(raw_bytes).decode("ascii"),
            )
        )
        return image_id
