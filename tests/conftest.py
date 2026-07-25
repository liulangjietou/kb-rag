"""Shared pytest fixtures: a TestClient plus minimal sample-file builders.

Every sample is generated in code (no binary fixtures committed to the
repo) using the same libraries the parsers themselves rely on, per the M1
verification requirement.
"""

import csv
import io

import pymupdf
import pytest
from docx import Document
from fastapi.testclient import TestClient
from openpyxl import Workbook

from app.main import app


@pytest.fixture()
def client() -> TestClient:
    return TestClient(app)


def make_pdf_bytes(text: str = "Hello kb-rag PDF") -> bytes:
    """Build a minimal one-page PDF using pymupdf (no reportlab available)."""
    document = pymupdf.open()
    page = document.new_page()
    page.insert_text((72, 72), text)
    data = document.tobytes()
    document.close()
    return data


def make_docx_bytes(heading: str = "Title", paragraph: str = "Hello kb-rag DOCX") -> bytes:
    document = Document()
    document.add_heading(heading, level=1)
    document.add_paragraph(paragraph)
    table = document.add_table(rows=2, cols=2)
    table.rows[0].cells[0].text = "Name"
    table.rows[0].cells[1].text = "Age"
    table.rows[1].cells[0].text = "Alice"
    table.rows[1].cells[1].text = "30"

    buffer = io.BytesIO()
    document.save(buffer)
    return buffer.getvalue()


def make_xlsx_bytes() -> bytes:
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "Sheet1"
    sheet.append(["Name", "Age"])
    sheet.append(["Alice", 30])
    sheet.append(["Bob", 25])

    buffer = io.BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


def make_csv_bytes() -> bytes:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["Name", "Age"])
    writer.writerow(["Alice", "30"])
    writer.writerow(["Bob", "25"])
    return buffer.getvalue().encode("utf-8")


def post_parse(client: TestClient, filename: str, content: bytes, file_ext: str):
    return client.post(
        "/api/v1/parse",
        files={"file": (filename, content, "application/octet-stream")},
        data={"file_ext": file_ext},
    )
