import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

from validate_config import (  # noqa: E402
    validate_env_example,
    validate_no_personal_paths,
    validate_synced_documents,
)


class ValidateConfigTest(unittest.TestCase):

    def test_valid_env_example_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.example"
            path.write_text("PORT=20000\nDATA_DIR=../demo\n", encoding="utf-8")

            self.assertEqual([], validate_env_example(path))

    def test_duplicate_env_key_reports_both_lines(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.example"
            path.write_text("MAX_TOKENS=3072\nMAX_TOKENS=2048\n", encoding="utf-8")

            issues = validate_env_example(path)

            self.assertEqual(1, len(issues))
            self.assertIn("duplicate environment key MAX_TOKENS", issues[0])
            self.assertIn("first declared at line 1", issues[0])

    def test_developer_machine_paths_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_path = root / ".env.example"
            yaml_path = root / "application.yml"
            env_path.write_text("DATA_DIR=/Users/example/project/demo\n", encoding="utf-8")
            yaml_path.write_text("data-dir: ${DATA_DIR:/home/example/demo}\n", encoding="utf-8")

            self.assertEqual(1, len(validate_env_example(env_path)))
            self.assertEqual(1, len(validate_no_personal_paths(yaml_path)))

    def test_requirement_document_copies_must_match(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            primary = root / "primary.md"
            mirror = root / "mirror.md"
            primary.write_text("same", encoding="utf-8")
            mirror.write_text("same", encoding="utf-8")
            self.assertEqual([], validate_synced_documents(primary, mirror))

            mirror.write_text("different", encoding="utf-8")
            self.assertEqual(1, len(validate_synced_documents(primary, mirror)))


if __name__ == "__main__":
    unittest.main()
