#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
kb-rag 压测种子数据生成脚本（scripts/seed-bench.py）
Author: owlzhangfq@gmail.com

覆盖 docs/M6-CONTRACTS.md §3 / §4 验收⑦：零 Key 直写 MySQL + Elasticsearch bulk，
灌入 >= 10 万分片（默认 10 万）的一个独立"种子知识库"，供 scripts/benchmark.sh 复用做
10 万分片规模压测，不依赖任何模型 Key（内容/BM25 检索路径足以验证 P95）。

写入范围（字段结构对齐 kb-rag-server Flyway V1__baseline.sql，见该文件 t_kb_knowledge_base /
t_kb_document / t_kb_document_version / t_kb_chunk / t_kb_index_registry 定义）：
  - t_kb_knowledge_base：一行种子知识库
  - t_kb_document / t_kb_document_version：--docs-count 篇文档，各一个 ACTIVE 版本，
    document.current_version_id 指向该版本（检索链路只认这个字段，不看 version 表的
    status/active_flag，但仍一并写正确值保持数据自洽）
  - t_kb_chunk：分片总数按文档均分，document_version_id 与 kb_id 对齐 ES 文档，
    enabled=1（检索默认只查 enabled 分片）
  - t_kb_index_registry：登记 ES 物理索引 + 别名（检索本身不查这张表，只是运维一致性）
  - Elasticsearch：物理索引 kb_{kbId}_none_v1（零 Key 三段命名，embedding 段=none，
    对齐 kb-rag-server IndexNaming 的 normalizeKbId/物理名算法），别名 kb_{kbId}_es
    （RetrievalService 实际查询走的是这个别名，不是物理索引名）；mapping 只含
    BM25 检索用得到的字段，不建 dense_vector（零 Key 模式本就不产出向量，压测足够）

幂等：脚本每次运行前，先用固定的 --kb-id（默认 kb_benchseed）识别并清理上一次生成的
同名种子库（MySQL 四张表 + ES 物理索引/别名），再重新生成，支持重复压测演练。

依赖：优先使用 PyMySQL（若已安装）直连 MySQL；未安装则回退到宿主机 `mysql` 命令行
客户端，通过管道执行 SQL（与 scripts/backup.sh 复用 mysql 客户端的风格一致）。
Elasticsearch 交互只用标准库 urllib + json，不引入第三方 HTTP 客户端。

用法：
  python3 scripts/seed-bench.py                              # 用 .env 配置，默认 10 万分片
  python3 scripts/seed-bench.py --chunk-count 200000 --batch-size 5000
  python3 scripts/seed-bench.py --clean-only                 # 只清理种子库，不重新生成
  python3 scripts/seed-bench.py --kb-id kb_benchseed2         # 换一个独立种子库 id

生成结束后打印 kb_id/ES 索引名/别名与耗时，可直接接到 scripts/benchmark.sh：
  KB_ID=kb_benchseed TOKEN=<登录 token> ./scripts/benchmark.sh
"""

import argparse
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

# ---------------------------------------------------------------------
# 常量（无魔法值散落在流程里，均集中在此）
# ---------------------------------------------------------------------
DEFAULT_CHUNK_COUNT = 100_000
DEFAULT_BATCH_SIZE = 2_000
DEFAULT_DOCS_COUNT = 50
DEFAULT_KB_ID = "kb_benchseed"
DEFAULT_KB_NAME = "压测种子知识库（scripts/seed-bench.py 自动生成，可重复清理重建）"
DEFAULT_ANALYZER = "standard"
DEFAULT_SEED = 42

KB_ID_PREFIX = "kb_"
ID_PREFIX_DOC = "doc"
ID_PREFIX_VERSION = "dv"
ID_PREFIX_CHUNK = "ck"
ID_SEPARATOR = "_"

# 对齐 kb-rag-server KbConstants / IndexNaming（零 Key 三段命名）
ES_EMBEDDING_SEGMENT_NONE = "none"
ES_SNAPSHOT_SEGMENT_V1 = "v1"
ES_INDEX_SCHEMA_VERSION = "1"

# 与 scripts/benchmark.sh 内置的 10 条查询同题材，保证种子内容真的能被那些查询召回
TOPIC_PHRASES = [
    "如何重置账号密码",
    "退款流程需要多久",
    "发票怎么申请开具",
    "会员权益包含哪些内容",
    "订单发货后多久能收到",
    "如何联系人工客服",
    "优惠券使用规则说明",
    "账号被冻结怎么办",
    "如何注销账号",
    "售后服务政策是什么",
]

FILLER_SENTENCES = [
    "本条目适用于标准业务流程，具体以系统内最新公告为准。",
    "如遇异常情况，请通过工单系统提交详细描述并附上凭证。",
    "客服会在受理后的两个工作日内给出处理结果反馈。",
    "相关条款可能随平台规则调整而更新，请以生效版本为准。",
    "该流程涉及多个内部系统协同处理，通常耗时以小时计。",
    "用户可在个人中心查看当前处理进度与历史记录。",
    "部分地区或渠道的执行细节可能存在差异，请以当地政策为准。",
    "如需加急处理，可在提交时勾选加急标记并说明原因。",
    "系统会在状态变更时通过短信或站内信通知用户。",
    "该说明仅作参考，具体执行以人工客服最终答复为准。",
    "长期未处理的工单会自动流转至上级客服进行复核。",
    "用户资料变更需要通过身份验证后才能生效。",
]


def log_info(msg: str) -> None:
    print(f"[INFO]  {msg}", flush=True)


def log_error(msg: str) -> None:
    print(f"[ERROR] {msg}", file=sys.stderr, flush=True)


def fatal(msg: str) -> None:
    log_error(msg)
    sys.exit(1)


# ---------------------------------------------------------------------
# .env 加载（与 scripts/backup.sh 相同惯例：KEY=VALUE，忽略注释/空行）
# ---------------------------------------------------------------------
def load_env_file(env_path: Path) -> dict:
    result = {}
    if not env_path.is_file():
        return result
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


def cfg(cli_value, env_key: str, env_file: dict, default):
    if cli_value is not None:
        return cli_value
    if env_key in os.environ and os.environ[env_key] != "":
        return os.environ[env_key]
    if env_key in env_file and env_file[env_key] != "":
        return env_file[env_key]
    return default


# ---------------------------------------------------------------------
# 业务 id 生成（形状对齐 kb-rag-server BizIdGenerator：prefix_ + 16 位小写 hex；
# 该仓库对业务 id 无正则校验，这里只需保证同前缀 + 唯一即可）
# ---------------------------------------------------------------------
def new_biz_id(prefix: str) -> str:
    return f"{prefix}{ID_SEPARATOR}{uuid.uuid4().hex[:16]}"


def normalize_kb_id(kb_id: str) -> str:
    """对齐 IndexNaming.normalizeKbId：字面量去掉 'kb_' 前缀后转小写。"""
    value = kb_id[len(KB_ID_PREFIX):] if kb_id.startswith(KB_ID_PREFIX) else kb_id
    return value.lower()


def physical_index_name(kb_id: str) -> str:
    return f"kb_{normalize_kb_id(kb_id)}_{ES_EMBEDDING_SEGMENT_NONE}_{ES_SNAPSHOT_SEGMENT_V1}"


def alias_name(kb_id: str) -> str:
    return f"kb_{normalize_kb_id(kb_id)}_es"


# ---------------------------------------------------------------------
# SQL 字面量转义（唯一的字符串拼接防御点：无论走 PyMySQL 的 DELETE/SELECT 拼接，
# 还是 CLI 回退路径的 INSERT 拼接，都经过这里）
# ---------------------------------------------------------------------
def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    text = str(value).replace("\\", "\\\\").replace("'", "\\'")
    return f"'{text}'"


# ---------------------------------------------------------------------
# MySQL 后端：优先 PyMySQL，未安装则回退 mysql CLI 管道
# ---------------------------------------------------------------------
class MySQLBackend:
    def insert_rows(self, table: str, columns, rows) -> None:
        raise NotImplementedError

    def execute_statement(self, sql: str) -> None:
        raise NotImplementedError

    def fetch_all(self, sql: str):
        raise NotImplementedError

    def fetch_scalar(self, sql: str):
        rows = self.fetch_all(sql)
        return rows[0][0] if rows and rows[0] else None

    def close(self) -> None:
        pass


class PyMySQLBackend(MySQLBackend):
    def __init__(self, host, port, user, password, database):
        import pymysql  # 局部导入：仅在确认可用时才 import，其余路径不受影响

        self._conn = pymysql.connect(
            host=host,
            port=int(port),
            user=user,
            password=password,
            database=database,
            charset="utf8mb4",
            autocommit=False,
        )

    def insert_rows(self, table, columns, rows) -> None:
        if not rows:
            return
        placeholders = ",".join(["%s"] * len(columns))
        sql = f"INSERT INTO {table} ({','.join(columns)}) VALUES ({placeholders})"
        with self._conn.cursor() as cursor:
            cursor.executemany(sql, rows)
        self._conn.commit()

    def execute_statement(self, sql: str) -> None:
        with self._conn.cursor() as cursor:
            cursor.execute(sql)
        self._conn.commit()

    def fetch_all(self, sql: str):
        with self._conn.cursor() as cursor:
            cursor.execute(sql)
            return cursor.fetchall()

    def close(self) -> None:
        self._conn.close()


class CliMySQLBackend(MySQLBackend):
    """未安装 PyMySQL 时的回退实现：所有交互通过 `mysql` 命令行客户端管道完成。"""

    def __init__(self, host, port, user, password, database):
        self._base_args = [
            "mysql",
            "-h", str(host),
            "-P", str(port),
            "-u", str(user),
            str(database),
        ]
        self._env = dict(os.environ)
        self._env["MYSQL_PWD"] = password

    def _run(self, sql: str, extra_args=None) -> str:
        args = self._base_args + (extra_args or [])
        proc = subprocess.run(
            args,
            input=sql.encode("utf-8"),
            env=self._env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"mysql CLI failed (exit={proc.returncode}): {proc.stderr.decode('utf-8', 'ignore').strip()}"
            )
        return proc.stdout.decode("utf-8", "ignore")

    def insert_rows(self, table, columns, rows) -> None:
        if not rows:
            return
        values_sql = ",".join(
            "(" + ",".join(sql_literal(v) for v in row) + ")" for row in rows
        )
        sql = f"INSERT INTO {table} ({','.join(columns)}) VALUES {values_sql};"
        self._run(sql)

    def execute_statement(self, sql: str) -> None:
        self._run(sql)

    def fetch_all(self, sql: str):
        # -N 去表头，-B 用 tab 分隔，便于按行/列切分
        output = self._run(sql, extra_args=["-N", "-B"])
        rows = []
        for line in output.splitlines():
            if line == "":
                continue
            rows.append(tuple(line.split("\t")))
        return rows


def build_mysql_backend(host, port, user, password, database) -> MySQLBackend:
    try:
        import pymysql  # noqa: F401

        log_info("using PyMySQL backend for MySQL access")
        return PyMySQLBackend(host, port, user, password, database)
    except ImportError:
        log_info("PyMySQL not installed, falling back to `mysql` CLI pipe backend")
        return CliMySQLBackend(host, port, user, password, database)


# ---------------------------------------------------------------------
# Elasticsearch 交互：仅标准库 urllib + json
# ---------------------------------------------------------------------
def es_request(es_uri: str, method: str, path: str, body=None):
    url = es_uri.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            parsed = json.loads(raw) if raw else {}
            return resp.status, parsed
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw.decode("utf-8", "ignore")}
        return exc.code, parsed
    except urllib.error.URLError as exc:
        raise RuntimeError(f"cannot reach Elasticsearch at {es_uri}: {exc}") from exc


def es_bulk(es_uri: str, ndjson_body: bytes):
    url = es_uri.rstrip("/") + "/_bulk"
    req = urllib.request.Request(
        url, data=ndjson_body, method="POST",
        headers={"Content-Type": "application/x-ndjson"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw.decode("utf-8", "ignore")}
        return exc.code, parsed


def es_delete_index_if_exists(es_uri: str, index_name: str) -> None:
    status, body = es_request(es_uri, "DELETE", "/" + index_name)
    if status not in (200, 404):
        fatal(f"failed to delete existing ES index {index_name}: http {status} {body}")


def es_create_index(es_uri: str, index_name: str, analyzer: str) -> None:
    # mapping 字段集合对齐 kb-rag-server EsIndexAdmin.buildProperties，零 Key 场景不含
    # dense_vector 字段（embedding 段=none 时 IndexSpec.hasVectorField()==false）
    mapping = {
        "settings": {"number_of_shards": 1, "number_of_replicas": 0},
        "mappings": {
            "_meta": {"schema_version": ES_INDEX_SCHEMA_VERSION},
            "properties": {
                "chunk_id": {"type": "keyword"},
                "kb_id": {"type": "keyword"},
                "doc_id": {"type": "keyword"},
                "document_version_id": {"type": "keyword"},
                "parent_id": {"type": "keyword"},
                "chunk_type": {"type": "keyword"},
                "enabled": {"type": "boolean"},
                "tag_ids": {"type": "keyword"},
                "session_id": {"type": "keyword"},
                "sender": {"type": "keyword"},
                "msg_time": {"type": "long"},
                "chunk_seq": {"type": "integer"},
                "content": {"type": "text", "analyzer": analyzer},
            },
        },
    }
    status, body = es_request(es_uri, "PUT", "/" + index_name, mapping)
    if status != 200:
        fatal(f"failed to create ES index {index_name}: http {status} {body}")


def es_create_alias(es_uri: str, index_name: str, alias: str) -> None:
    body = {"actions": [{"add": {"index": index_name, "alias": alias}}]}
    status, resp = es_request(es_uri, "POST", "/_aliases", body)
    if status != 200:
        fatal(f"failed to create ES alias {alias} -> {index_name}: http {status} {resp}")


# ---------------------------------------------------------------------
# 幂等清理：按固定 kb_id 找回上一次的种子库并整体清掉（MySQL 四表 + ES 物理索引）
# ---------------------------------------------------------------------
def cleanup_existing_seed(backend: MySQLBackend, es_uri: str, kb_id: str) -> None:
    log_info(f"cleaning up any previous seed data for kb_id={kb_id}")
    doc_rows = backend.fetch_all(
        f"SELECT doc_id FROM t_kb_document WHERE kb_id={sql_literal(kb_id)}"
    )
    doc_ids = [row[0] for row in doc_rows]

    backend.execute_statement(f"DELETE FROM t_kb_chunk WHERE kb_id={sql_literal(kb_id)}")
    if doc_ids:
        in_clause = ",".join(sql_literal(d) for d in doc_ids)
        backend.execute_statement(
            f"DELETE FROM t_kb_document_version WHERE doc_id IN ({in_clause})"
        )
    backend.execute_statement(f"DELETE FROM t_kb_document WHERE kb_id={sql_literal(kb_id)}")
    backend.execute_statement(
        f"DELETE FROM t_kb_index_registry WHERE kb_id={sql_literal(kb_id)}"
    )
    backend.execute_statement(
        f"DELETE FROM t_kb_knowledge_base WHERE kb_id={sql_literal(kb_id)}"
    )

    es_delete_index_if_exists(es_uri, physical_index_name(kb_id))
    log_info(f"cleanup done, removed {len(doc_ids)} previous document(s) if any existed")


# ---------------------------------------------------------------------
# 分片内容生成：主题短语命中 scripts/benchmark.sh 内置查询，填充句子拉长文本
# ---------------------------------------------------------------------
def build_chunk_content(rng: random.Random, seq: int) -> str:
    topic = rng.choice(TOPIC_PHRASES)
    fillers = rng.sample(FILLER_SENTENCES, k=rng.randint(3, 6))
    return f"{topic}。" + "".join(fillers) + f"（分片编号 seq={seq}）"


# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------
def parse_args():
    parser = argparse.ArgumentParser(
        description="kb-rag zero-key benchmark seed generator (MySQL + Elasticsearch bulk load)"
    )
    parser.add_argument("--chunk-count", type=int, default=None, help=f"default {DEFAULT_CHUNK_COUNT}")
    parser.add_argument("--batch-size", type=int, default=None, help=f"default {DEFAULT_BATCH_SIZE}")
    parser.add_argument("--docs-count", type=int, default=None, help=f"default {DEFAULT_DOCS_COUNT}")
    parser.add_argument("--kb-id", type=str, default=None, help=f"default {DEFAULT_KB_ID}")
    parser.add_argument("--kb-name", type=str, default=None, help=f"default {DEFAULT_KB_NAME!r}")
    parser.add_argument("--analyzer", type=str, default=None, help=f"ES content analyzer, default {DEFAULT_ANALYZER}")
    parser.add_argument("--seed", type=int, default=None, help=f"random seed, default {DEFAULT_SEED}")
    parser.add_argument("--clean-only", action="store_true", help="only remove previous seed data, skip regeneration")
    parser.add_argument("--mysql-host", type=str, default=None)
    parser.add_argument("--mysql-port", type=int, default=None)
    parser.add_argument("--mysql-db", type=str, default=None)
    parser.add_argument("--mysql-user", type=str, default=None)
    parser.add_argument("--mysql-password", type=str, default=None)
    parser.add_argument("--es-uri", type=str, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    env_file = load_env_file(repo_root / ".env")

    chunk_count = int(cfg(args.chunk_count, "SEED_BENCH_CHUNK_COUNT", env_file, DEFAULT_CHUNK_COUNT))
    batch_size = int(cfg(args.batch_size, "SEED_BENCH_BATCH_SIZE", env_file, DEFAULT_BATCH_SIZE))
    docs_count = int(cfg(args.docs_count, "SEED_BENCH_DOCS_COUNT", env_file, DEFAULT_DOCS_COUNT))
    kb_id = cfg(args.kb_id, "SEED_BENCH_KB_ID", env_file, DEFAULT_KB_ID)
    kb_name = cfg(args.kb_name, "SEED_BENCH_KB_NAME", env_file, DEFAULT_KB_NAME)
    analyzer = cfg(args.analyzer, "SEED_BENCH_ANALYZER", env_file, DEFAULT_ANALYZER)
    seed = int(cfg(args.seed, "SEED_BENCH_SEED", env_file, DEFAULT_SEED))

    mysql_host = cfg(args.mysql_host, "MYSQL_HOST", env_file, "127.0.0.1")
    mysql_port = cfg(args.mysql_port, "MYSQL_PORT", env_file, 3306)
    mysql_db = cfg(args.mysql_db, "MYSQL_DB", env_file, "kb_rag")
    mysql_user = cfg(args.mysql_user, "MYSQL_USER", env_file, "kbrag")
    mysql_password = cfg(args.mysql_password, "MYSQL_PASSWORD", env_file, "")
    es_uri = cfg(args.es_uri, "ES_URI", env_file, "http://127.0.0.1:9200")

    if chunk_count <= 0:
        fatal(f"--chunk-count must be positive, got {chunk_count}")
    if batch_size <= 0:
        fatal(f"--batch-size must be positive, got {batch_size}")
    if docs_count <= 0:
        fatal(f"--docs-count must be positive, got {docs_count}")
    if not mysql_password:
        fatal("MySQL password is empty, set MYSQL_PASSWORD in .env or pass --mysql-password")

    log_info(
        f"config: kb_id={kb_id} chunk_count={chunk_count} docs_count={docs_count} "
        f"batch_size={batch_size} mysql={mysql_user}@{mysql_host}:{mysql_port}/{mysql_db} es={es_uri}"
    )

    backend = build_mysql_backend(mysql_host, mysql_port, mysql_user, mysql_password, mysql_db)

    try:
        backend.fetch_all("SELECT 1")
    except Exception as exc:  # noqa: BLE001 - fast-fail with an actionable message
        fatal(
            f"cannot connect to MySQL ({mysql_user}@{mysql_host}:{mysql_port}/{mysql_db}): {exc}. "
            f"is docker compose up -d running and MYSQL_* in .env correct?"
        )

    try:
        es_request(es_uri, "GET", "/")
    except RuntimeError as exc:
        fatal(f"{exc}. is docker compose up -d running for Elasticsearch?")

    cleanup_existing_seed(backend, es_uri, kb_id)

    if args.clean_only:
        log_info("clean-only mode requested, skipping regeneration")
        backend.close()
        return 0

    started_at = time.time()
    rng = random.Random(seed)

    idx_name = physical_index_name(kb_id)
    idx_alias = alias_name(kb_id)
    log_info(f"creating ES index [{idx_name}] with alias [{idx_alias}] (analyzer={analyzer})")
    es_create_index(es_uri, idx_name, analyzer)
    es_create_alias(es_uri, idx_name, idx_alias)

    log_info(f"inserting knowledge base row kb_id={kb_id}")
    backend.insert_rows(
        "t_kb_knowledge_base",
        ["kb_id", "name", "description"],
        [(kb_id, kb_name, "auto-generated by scripts/seed-bench.py for benchmarking, safe to clean up")],
    )

    # 分片总数按文档均分，余数分给前几篇文档
    base_chunks_per_doc = chunk_count // docs_count
    remainder = chunk_count % docs_count
    doc_specs = []  # (doc_id, version_id, chunk_start_seq_global, chunk_count_for_doc)
    cursor = 0
    for doc_index in range(docs_count):
        count_for_doc = base_chunks_per_doc + (1 if doc_index < remainder else 0)
        if count_for_doc == 0:
            continue
        doc_id = new_biz_id(ID_PREFIX_DOC)
        version_id = new_biz_id(ID_PREFIX_VERSION)
        doc_specs.append((doc_id, version_id, cursor, count_for_doc))
        cursor += count_for_doc

    log_info(f"inserting {len(doc_specs)} document + document_version rows")
    backend.insert_rows(
        "t_kb_document",
        ["doc_id", "kb_id", "file_name", "file_ext", "current_version_id", "process_status", "config_stale"],
        [
            (doc_id, kb_id, f"bench-seed-doc-{i:04d}.md", "md", version_id, "INDEXED", 0)
            for i, (doc_id, version_id, _, _) in enumerate(doc_specs)
        ],
    )
    backend.insert_rows(
        "t_kb_document_version",
        ["version_id", "doc_id", "version", "status", "active_flag", "changelog"],
        [
            (version_id, doc_id, "1.0", "ACTIVE", 1, "generated by scripts/seed-bench.py")
            for doc_id, version_id, _, _ in doc_specs
        ],
    )

    log_info(f"inserting index registry row: engine=es physical_index_name={idx_name} alias={idx_alias}")
    backend.insert_rows(
        "t_kb_index_registry",
        [
            "kb_id", "engine", "physical_index_name", "alias_name", "is_current",
            "embedding_provider", "embedding_version", "snapshot_version", "schema_version", "status",
        ],
        [(kb_id, "es", idx_name, idx_alias, 1, None, ES_EMBEDDING_SEGMENT_NONE, ES_SNAPSHOT_SEGMENT_V1, ES_INDEX_SCHEMA_VERSION, "ACTIVE")],
    )

    log_info(f"generating and inserting {chunk_count} chunks in batches of {batch_size} (MySQL + ES bulk)")
    total_inserted = 0
    chunk_batch_rows = []
    es_ndjson_lines = []

    def flush_batch():
        nonlocal chunk_batch_rows, es_ndjson_lines, total_inserted
        if not chunk_batch_rows:
            return
        backend.insert_rows(
            "t_kb_chunk",
            ["chunk_id", "kb_id", "doc_id", "document_version_id", "content", "seq", "chunk_type", "enabled", "embedding_status"],
            chunk_batch_rows,
        )
        body = ("\n".join(es_ndjson_lines) + "\n").encode("utf-8")
        status, resp = es_bulk(es_uri, body)
        if status != 200 or resp.get("errors"):
            first_error = None
            for item in resp.get("items", []):
                action_result = item.get("index", {})
                if action_result.get("error"):
                    first_error = action_result["error"]
                    break
            fatal(f"ES bulk indexing failed (http {status}): {first_error or resp}")
        total_inserted += len(chunk_batch_rows)
        chunk_batch_rows = []
        es_ndjson_lines = []
        log_info(f"progress: {total_inserted}/{chunk_count} chunks written")

    for doc_id, version_id, start_seq, count_for_doc in doc_specs:
        for offset in range(count_for_doc):
            seq = start_seq + offset
            chunk_id = new_biz_id(ID_PREFIX_CHUNK)
            content = build_chunk_content(rng, seq)

            chunk_batch_rows.append(
                (chunk_id, kb_id, doc_id, version_id, content, offset, "TEXT", 1, "SKIPPED")
            )
            es_ndjson_lines.append(json.dumps({"index": {"_index": idx_name, "_id": chunk_id}}))
            es_ndjson_lines.append(
                json.dumps(
                    {
                        "chunk_id": chunk_id,
                        "kb_id": kb_id,
                        "doc_id": doc_id,
                        "document_version_id": version_id,
                        "parent_id": None,
                        "chunk_type": "TEXT",
                        "enabled": True,
                        "chunk_seq": offset,
                        "content": content,
                    },
                    ensure_ascii=False,
                )
            )

            if len(chunk_batch_rows) >= batch_size:
                flush_batch()

    flush_batch()
    backend.close()

    elapsed = time.time() - started_at
    log_info("------------------------------------------------------------")
    log_info(f"seed finished: kb_id={kb_id} chunks={total_inserted} docs={len(doc_specs)} elapsed={elapsed:.1f}s")
    log_info(f"ES physical index: {idx_name}   alias (queried by RetrievalService): {idx_alias}")
    log_info(f"benchmark it with: KB_ID={kb_id} TOKEN=<login token> ./scripts/benchmark.sh")
    log_info(f"clean it up later with: python3 scripts/seed-bench.py --kb-id {kb_id} --clean-only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
