#!/usr/bin/env python3
"""校验单仓配置事实源，阻止重复环境变量和开发机路径进入版本库。"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ENV_ASSIGNMENT = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")
PERSONAL_PATH = re.compile(r"^(?:/Users/[^/]+/|/home/[^/]+/|[A-Za-z]:\\Users\\[^\\]+\\)")


def validate_env_example(path: Path) -> list[str]:
    """检查环境变量模板中的重复键和开发机绝对路径。"""
    issues: list[str] = []
    first_lines: dict[str, int] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = ENV_ASSIGNMENT.match(raw_line.strip())
        if match is None:
            continue
        key, raw_value = match.groups()
        if key in first_lines:
            issues.append(
                f"{path}:{line_number}: duplicate environment key {key} "
                f"(first declared at line {first_lines[key]})"
            )
        else:
            first_lines[key] = line_number
        value = raw_value.strip().strip("'\"")
        if PERSONAL_PATH.match(value):
            issues.append(f"{path}:{line_number}: {key} contains a developer-machine absolute path")
    return issues


def validate_no_personal_paths(path: Path) -> list[str]:
    """检查应用默认配置中是否固化了 macOS/Linux/Windows 用户目录。"""
    issues: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if re.search(r"/Users/[^/\s]+/|/home/[^/\s]+/|[A-Za-z]:\\Users\\[^\\\s]+\\", line):
            issues.append(f"{path}:{line_number}: contains a developer-machine absolute path")
    return issues


def validate_synced_documents(primary: Path, mirror: Path) -> list[str]:
    """需求文档有两处入口，要求内容逐字一致，避免阅读到不同契约。"""
    if primary.read_bytes() == mirror.read_bytes():
        return []
    return [f"{primary} and {mirror} differ; update both requirement document copies together"]


def validate_repository(repo_root: Path) -> list[str]:
    """执行仓库级配置契约检查。"""
    deploy_root = repo_root / "kb-rag-deploy"
    issues = validate_env_example(deploy_root / ".env.example")
    issues.extend(
        validate_no_personal_paths(
            repo_root / "kb-rag-server/kb-api/src/main/resources/application.yml"
        )
    )
    issues.extend(
        validate_synced_documents(
            repo_root / "docs/知识库需求文档.md",
            deploy_root / "docs/知识库需求文档.md",
        )
    )
    return issues


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    issues = validate_repository(repo_root)
    if issues:
        for issue in issues:
            print(f"[ERROR] {issue}", file=sys.stderr)
        print(f"[ERROR] configuration validation failed with {len(issues)} issue(s)", file=sys.stderr)
        return 1
    print("[INFO] configuration validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
