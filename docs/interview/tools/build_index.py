#!/usr/bin/env python3
"""生成面试题库的题目索引，并校验编号与互链。

Author: owlzhangfq@gmail.com

用法：
    python3 docs/interview/tools/build_index.py          # 重新生成 题目索引.md
    python3 docs/interview/tools/build_index.py --check  # 只校验；有问题或索引过期时返回 1
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

INTERVIEW_DIR = Path(__file__).resolve().parent.parent
INDEX_PATH = INTERVIEW_DIR / "题目索引.md"

# 编号前缀 -> (源文件, 标题级别, 编号位数, 题库名称)
BANKS = {
    "R": (INTERVIEW_DIR.parent / "RAG面试八股.md", 2, 2, "RAG 工程面试八股（实战版）"),
    "P": (INTERVIEW_DIR / "01-项目全量面试题.md", 3, 3, "项目全量面试题"),
    "T": (INTERVIEW_DIR / "02-知识库八股面试题.md", 3, 3, "知识库八股面试题"),
    "B": (INTERVIEW_DIR / "03-知识库构建最佳实践.md", 2, 2, "知识库构建最佳实践"),
    "S": (INTERVIEW_DIR / "04-场景排障与系统设计题.md", 3, 2, "场景排障与系统设计题"),
}
BANK_ORDER = ["P", "T", "S", "B", "R"]
DIFFICULTIES = ["★", "★★", "★★★"]

ID_PATTERN = r"(?:P\d{3}|T\d{3}|R\d{2}|S\d{2}|B\d{2})"
ID_REF = re.compile(r"(?<![A-Za-z0-9_])(" + ID_PATTERN + r")(?![0-9])")
META_LINE = re.compile(r"^> 标签：(?P<tags>.+?) ｜ 难度：(?P<diff>★+)(?: ｜ 关联：(?P<refs>.+))?$")


@dataclass
class Question:
    """一道题的索引信息。"""

    qid: str
    title: str
    path: Path
    line: int
    tags: list[str] = field(default_factory=list)
    difficulty: str = ""
    related: list[str] = field(default_factory=list)
    body_refs: list[str] = field(default_factory=list)


def parse_bank(prefix: str) -> tuple[list[Question], list[str]]:
    """解析单个题库文件，返回题目列表与格式问题。"""
    path, level, width, _ = BANKS[prefix]
    heading = re.compile(r"^" + "#" * level + r" (" + prefix + r"\d{" + str(width) + r"}) (.+)$")
    questions: list[Question] = []
    issues: list[str] = []
    in_code = False
    lines = path.read_text(encoding="utf-8").split("\n")
    for number, line in enumerate(lines, start=1):
        if line.startswith("```"):
            in_code = not in_code
            continue
        if in_code:
            if line.startswith("# "):
                issues.append(f"{path.name}:{number}: code line starts with '# ', heading splitter would cut here")
            continue
        match = heading.match(line)
        if match:
            questions.append(Question(match.group(1), match.group(2).strip(), path, number))
            meta = lines[number + 1] if number + 1 < len(lines) else ""
            meta_match = META_LINE.match(meta)
            if lines[number] != "" or not meta_match:
                issues.append(f"{path.name}:{number}: {match.group(1)} lacks the meta line below its title")
                continue
            current = questions[-1]
            current.tags = [tag.strip() for tag in meta_match.group("tags").split("、") if tag.strip()]
            current.difficulty = meta_match.group("diff")
            if meta_match.group("refs"):
                current.related = expand_refs(meta_match.group("refs"))
            continue
        if questions and not line.startswith("> 标签："):
            questions[-1].body_refs.extend(expand_refs(line))
    if in_code:
        issues.append(f"{path.name}: unbalanced code fence")
    return questions, issues


def expand_refs(text: str) -> list[str]:
    """提取文本中的编号，区间写法（如 R17–R29）展开为区间内每个编号。"""
    refs: list[str] = []
    for start, end in re.findall(r"(" + ID_PATTERN + r")\s*[–-]\s*(" + ID_PATTERN + r")", text):
        if start[0] == end[0]:
            width = len(start) - 1
            for value in range(int(start[1:]), int(end[1:]) + 1):
                refs.append(f"{start[0]}{value:0{width}d}")
    refs.extend(ID_REF.findall(text))
    return refs


def validate(banks: dict[str, list[Question]]) -> list[str]:
    """校验编号连续唯一、难度合法、互链可解析。"""
    issues: list[str] = []
    known = {q.qid for questions in banks.values() for q in questions}
    for prefix, questions in banks.items():
        for expected, question in enumerate(questions, start=1):
            if int(question.qid[1:]) != expected:
                issues.append(f"{question.path.name}:{question.line}: {question.qid} breaks the sequence, "
                              f"expected {prefix}{expected}")
        duplicates = [qid for qid, count in Counter(q.qid for q in questions).items() if count > 1]
        issues.extend(f"{prefix}: duplicate id {qid}" for qid in duplicates)
        for question in questions:
            if question.difficulty and question.difficulty not in DIFFICULTIES:
                issues.append(f"{question.path.name}:{question.line}: bad difficulty {question.difficulty}")
            for ref in question.related + question.body_refs:
                if ref not in known:
                    issues.append(f"{question.path.name}: {question.qid} references unknown id {ref}")
    return issues


def render(banks: dict[str, list[Question]]) -> str:
    """渲染题目索引 Markdown。"""
    total = sum(len(questions) for questions in banks.values())
    out = [
        "# 题目索引",
        "",
        "> 本文件由 `python3 docs/interview/tools/build_index.py` 生成，请勿手工修改；改题目后重新生成。",
        f"> 共 {total} 题 / 节。按编号查：在对应文件里搜索编号；按知识点查：看「标签索引」；按关键词查：在本页搜索题目文字。",
        "",
        "## 统计",
        "",
        "| 编号 | 题库 | 数量 | ★ | ★★ | ★★★ |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for prefix in BANK_ORDER:
        questions = banks[prefix]
        path, _, _, name = BANKS[prefix]
        counts = Counter(q.difficulty for q in questions)
        span = f"{questions[0].qid}–{questions[-1].qid}"
        out.append(f"| {span} | [{name}]({link(path)}) | {len(questions)} | "
                   + " | ".join(str(counts[d]) for d in DIFFICULTIES) + " |")

    tag_index: dict[str, list[str]] = defaultdict(list)
    for prefix in BANK_ORDER:
        for question in banks[prefix]:
            for tag in question.tags:
                tag_index[tag].append(question.qid)
    out += ["", "## 标签索引", "", "| 标签 | 数量 | 编号 |", "| --- | --- | --- |"]
    for tag, ids in sorted(tag_index.items(), key=lambda item: (-len(item[1]), item[0])):
        out.append(f"| {escape(tag)} | {len(ids)} | {'、'.join(ids)} |")

    out += ["", "## 全部题目"]
    for prefix in BANK_ORDER:
        path, _, _, name = BANKS[prefix]
        out += ["", f"### {prefix} · {name}", "", f"来源：[{path.name}]({link(path)})", "",
                "| 编号 | 题目 | 难度 | 标签 |", "| --- | --- | --- | --- |"]
        for question in banks[prefix]:
            out.append(f"| {question.qid} | {escape(question.title)} | {question.difficulty} | "
                       f"{escape('、'.join(question.tags))} |")
    return "\n".join(out) + "\n"


def link(path: Path) -> str:
    """生成相对于索引文件的链接。"""
    return "../RAG面试八股.md" if path.parent != INTERVIEW_DIR else path.name


def escape(text: str) -> str:
    """转义表格分隔符。"""
    return text.replace("|", "\\|")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build or check the interview question index.")
    parser.add_argument("--check", action="store_true", help="validate only, fail when the index is stale")
    args = parser.parse_args()

    banks: dict[str, list[Question]] = {}
    issues: list[str] = []
    for prefix in BANKS:
        questions, bank_issues = parse_bank(prefix)
        banks[prefix] = questions
        issues.extend(bank_issues)
    issues.extend(validate(banks))
    content = render(banks)
    if args.check:
        current = INDEX_PATH.read_text(encoding="utf-8") if INDEX_PATH.exists() else ""
        if current != content:
            issues.append(f"{INDEX_PATH.name} is stale, run build_index.py to regenerate it")
    else:
        INDEX_PATH.write_text(content, encoding="utf-8")
    for issue in issues:
        print(f"ERROR {issue}")
    total = sum(len(questions) for questions in banks.values())
    print(f"checked {total} questions, {len(issues)} issue(s)")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
