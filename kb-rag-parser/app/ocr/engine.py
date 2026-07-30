"""PaddleOCR fallback engine for scanned PDF pages (M8-CONTRACTS.md §0.4).

Three-tier ownership of "who OCRs a scanned page's PNG render": kb-rag-server
VLM (has a key, status quo) -> this service's optional PaddleOCR fallback
(``OCR_ENGINE=paddle``, offline/zero-key) -> skip and degrade (status quo).
This module only implements the middle tier, and only that: it is invoked
from app/parsers/pdf.py once per scanned page, never called for a page that
already has a usable text layer.

paddleocr/paddlepaddle are optional dependencies (requirements-ocr.txt, not
installed by default -- keeps the base image lean per requirement §4.2's
"no reason for this service to phone home" posture, and per M8-CONTRACTS.md
§0.4 "默认不装保镜像体积"). ``ensure_ocr_engine_ready()`` is called once at
process startup (see app/main.py) and fast-fails with an actionable
RuntimeError when ``OCR_ENGINE=paddle`` is configured but the package isn't
installed, instead of a confusing failure buried inside the first request
that happens to hit a scanned page.

Author: owlzhangfq@gmail.com
"""

import logging
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

from app import config
from app.errors import ErrorCode

logger = logging.getLogger(__name__)

# Bounds a single page's OCR call under OCR_TIMEOUT_S from within the
# parser's own worker thread. OCR calls do run one at a time *per* in-flight
# parse (one page after another), but this executor is shared across all of
# them, so its size has to cover PARSER_EXECUTOR_MAX_WORKERS parses each
# holding one slot. The previous fixed 2 silently capped a scanned-document
# batch at two concurrent pages no matter how many parser workers were free:
# the pool existed only to carry the timeout, yet it had become the narrowest
# point in the chain. Sizing it off the worker count keeps it a timeout
# carrier rather than a throughput limit.
_ocr_call_executor = ThreadPoolExecutor(
    max_workers=config.PARSER_EXECUTOR_MAX_WORKERS, thread_name_prefix="ocr-worker"
)


class OcrEngine:
    """Strategy interface for the scanned-page OCR fallback.

    recognize() returns the extracted text, or None if the page could not
    be OCR'd (not installed / timed out / raised) -- callers must treat
    None as a per-page skip (fall back to pre-M8 behavior for that one
    page), never as a whole-document failure.
    """

    def recognize(self, png_bytes: bytes, page_no: int) -> Optional[str]:
        raise NotImplementedError


class NoOpOcrEngine(OcrEngine):
    """OCR_ENGINE=none (the default): scanned pages behave exactly as
    before M8 -- rendered to PNG, no text backfilled, no ocr_source set."""

    def recognize(self, png_bytes: bytes, page_no: int) -> Optional[str]:
        return None


class PaddleOcrEngine(OcrEngine):
    """Lazily loads ch_PP-OCRv4 (Chinese+English) on first use, so process
    startup (and every parse that never actually hits a scanned page)
    stays fast even with OCR_ENGINE=paddle configured."""

    def __init__(self) -> None:
        self._model = None

    def _get_model(self):
        if self._model is None:
            from paddleocr import PaddleOCR  # requirements-ocr.txt, optional

            # PaddleOCR 3.x API (requirements-ocr.txt pins 3.x): the 2.x kwargs
            # use_angle_cls/show_log are gone, textline orientation replaced them, and
            # document-level preprocessing models are disabled because this engine only
            # ever sees single rendered PDF pages that are already upright.
            self._model = PaddleOCR(
                lang=config.OCR_MODEL_LANG,
                ocr_version=config.OCR_MODEL_VERSION,
                use_textline_orientation=True,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
            )
        return self._model

    def recognize(self, png_bytes: bytes, page_no: int) -> Optional[str]:
        future = _ocr_call_executor.submit(self._run_inference, png_bytes)
        try:
            return future.result(timeout=config.OCR_TIMEOUT_S)
        except Exception as exc:  # noqa: broad-except -- any inference/timeout failure just skips this page
            future.cancel()
            logger.info(
                "ocr page skipped, reason=inference_failed_or_timeout, pageNo=%d, timeoutSeconds=%d, detail=%s",
                page_no,
                config.OCR_TIMEOUT_S,
                exc,
            )
            return None

    def _run_inference(self, png_bytes: bytes) -> Optional[str]:
        import cv2
        import numpy as np

        image_array = cv2.imdecode(np.frombuffer(png_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
        # PaddleOCR 3.x predict() returns one result mapping per input image with the
        # recognized line texts under "rec_texts".
        result = self._get_model().predict(image_array)
        lines = []
        for item in result or []:
            for text in item.get("rec_texts") or []:
                if text:
                    lines.append(text)
        return "\n".join(lines) if lines else None


def is_paddleocr_installed() -> bool:
    try:
        import paddleocr  # noqa: F401
    except ImportError:
        return False
    return True


def ensure_ocr_engine_ready() -> None:
    """Fast-fail app startup when OCR_ENGINE=paddle is configured but the
    optional 'paddleocr' package is not installed (M8-CONTRACTS.md §0.4)."""
    if config.OCR_ENGINE != config.OCR_ENGINE_PADDLE:
        return
    if not is_paddleocr_installed():
        logger.error(
            "ocr engine unavailable at startup, errorCode=%s, ocrEngine=%s",
            ErrorCode.PARSE_FAILED,
            config.OCR_ENGINE,
        )
        raise RuntimeError(
            "OCR_ENGINE=paddle is configured but the 'paddleocr' package is not installed; "
            "install it via 'pip install -r requirements-ocr.txt', or set OCR_ENGINE=none"
        )
    logger.info(
        "ocr engine ready, ocrEngine=%s, model=%s_%s",
        config.OCR_ENGINE,
        config.OCR_MODEL_LANG,
        config.OCR_MODEL_VERSION,
    )


_paddle_engine_singleton: Optional[PaddleOcrEngine] = None


def get_ocr_engine() -> OcrEngine:
    """Read config.OCR_ENGINE fresh on every call (never cached at import
    time) so tests can monkeypatch it per-case -- same pattern as
    app.parsers.images.ImageAssetCollector reading MAX_IMAGES_PER_DOC /
    MAX_IMAGE_BYTES from app.config at construction time, not import time."""
    global _paddle_engine_singleton
    if config.OCR_ENGINE == config.OCR_ENGINE_PADDLE:
        if _paddle_engine_singleton is None:
            _paddle_engine_singleton = PaddleOcrEngine()
        return _paddle_engine_singleton
    return NoOpOcrEngine()
