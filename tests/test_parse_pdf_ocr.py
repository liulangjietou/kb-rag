"""M8 PaddleOCR fallback tests (M8-CONTRACTS.md §0.4): OCR_ENGINE tri-state
switch behavior. Real PaddleOCR inference is only exercised when the
optional dependency (requirements-ocr.txt) is actually installed in this
environment -- otherwise that one test is skipped, per the M8 verification
requirement ("OCR 真实推理用例加 skip 标记按依赖是否安装").
"""

import pytest

from tests.conftest import make_scanned_pdf_bytes, make_scanned_pdf_with_ocrable_text_bytes, post_parse

try:
    import paddleocr  # noqa: F401

    _PADDLEOCR_INSTALLED = True
except ImportError:
    _PADDLEOCR_INSTALLED = False


def test_ocr_engine_none_is_default_and_scanned_page_behavior_is_unchanged(client):
    from app import config

    assert config.OCR_ENGINE == config.OCR_ENGINE_NONE  # the documented default

    content = make_scanned_pdf_bytes()
    response = post_parse(client, "scanned.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    page = body["data"]["pages"][0]
    assert page["scanned"] is True
    assert page["text"] == ""
    assert page.get("ocr_source") is None


def test_get_ocr_engine_reads_config_per_call(monkeypatch):
    from app import config
    from app.ocr.engine import NoOpOcrEngine, PaddleOcrEngine, get_ocr_engine

    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_NONE)
    assert isinstance(get_ocr_engine(), NoOpOcrEngine)

    # Constructing PaddleOcrEngine must not itself require paddleocr to be
    # installed (the real dependency is only imported lazily on first
    # recognize() call) -- get_ocr_engine() must stay cheap/safe to call
    # even when OCR_ENGINE=paddle is misconfigured without the package.
    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_PADDLE)
    assert isinstance(get_ocr_engine(), PaddleOcrEngine)


def test_ensure_ocr_engine_ready_fast_fails_when_paddle_not_installed(monkeypatch):
    if _PADDLEOCR_INSTALLED:
        pytest.skip("paddleocr is installed in this environment; fast-fail path not exercisable")

    from app import config
    from app.ocr.engine import ensure_ocr_engine_ready

    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_PADDLE)

    with pytest.raises(RuntimeError, match="paddleocr"):
        ensure_ocr_engine_ready()


def test_ensure_ocr_engine_ready_is_a_noop_for_ocr_engine_none(monkeypatch):
    from app import config
    from app.ocr.engine import ensure_ocr_engine_ready

    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_NONE)
    ensure_ocr_engine_ready()  # must not raise


@pytest.mark.skipif(
    not _PADDLEOCR_INSTALLED,
    reason="paddleocr not installed; install requirements-ocr.txt to run real OCR inference",
)
def test_ocr_engine_paddle_recognizes_scanned_page_text_and_sets_ocr_source(client, monkeypatch):
    from app import config

    monkeypatch.setattr(config, "OCR_ENGINE", config.OCR_ENGINE_PADDLE)

    content = make_scanned_pdf_with_ocrable_text_bytes("HELLO OCR")
    response = post_parse(client, "scanned.pdf", content, "pdf")

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "OK"
    page = body["data"]["pages"][0]
    assert page["scanned"] is True
    assert page["ocr_source"] == "paddle"
    assert page["text"].strip() != ""
