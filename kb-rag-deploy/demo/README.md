# kb-rag Demo 数据集（M3）

开箱即用的 Demo 素材：一套公开无版权、自撰内容的技术说明文档（覆盖 pdf/docx/xlsx/md
四种格式）+ 文档清单 + 示例评测集，配合 `POST /api/v1/system/demo/import`
（见 [`docs/M3-CONTRACTS.md` §3.7](../docs/M3-CONTRACTS.md)）实现"一键导入 Demo
知识库"，让首次启动即可跑通"上传 → 解析 → 检索"全链路。

## 目录结构

```
demo/
├── manifest.json      # 文档清单：文件名、标题、说明、建议 query 列表
├── eval-cases.json     # 示例评测集（本期只分发，导入功能见下方说明）
├── docs/                # 实际文档文件（demo/import 按 manifest.json 逐个导入）
│   ├── 01-rag-intro.md
│   ├── 02-document-parsing-and-chunking.docx
│   ├── 03-hybrid-retrieval-and-rerank.pdf
│   └── 04-eval-metrics-comparison.xlsx
└── tools/
    ├── generate_demo_docs.py   # docx/pdf/xlsx 的可复现生成脚本（md 为手写纯文本，无需生成）
    └── requirements.txt
```

`DEMO_DATA_DIR` 环境变量（见 `.env.example`）指向本目录（容器内默认
`/opt/kb-rag/demo`，本地开发默认指向本仓库 `demo/` 的绝对路径）；`demo/import`
读取 `${DEMO_DATA_DIR}/manifest.json`，按 `documents[].file_name`（相对本目录的
路径，如 `docs/01-rag-intro.md`）逐个上传进新建的"Demo 知识库"。

## 文档内容

四篇文档均为本项目原创撰写的 RAG / 知识库技术说明（中文，各篇正文 300-800
字），刻意覆盖不同格式以验证对应解析链路：

| 文件 | 格式 | 验证点 |
|---|---|---|
| `01-rag-intro.md` | Markdown | 纯文本解析、按语义/长度切分 |
| `02-document-parsing-and-chunking.docx` | Word | 多级标题解析、按标题层级切分 |
| `03-hybrid-retrieval-and-rerank.pdf` | PDF | 文本抽取 + **内嵌图片解析**（自绘流水线示意图，`kind=embedded`）、VLM 图文理解与文本代理插回原位（`docs/M3-CONTRACTS.md` §2.1/§3.2） |
| `04-eval-metrics-comparison.xlsx` | Excel | 多 sheet、表头拼装、行级切分 |

`manifest.json` 中每个文档附 `suggested_queries`（建议检索 query），供导入后在
检索调试页快速验证召回效果，无需用户自己想问题。

## 示例评测集（`eval-cases.json`）

结构对应需求文档 §6 `t_kb_eval_case` 字段：`query`、可选 `expected_answer`、
`anchor_type`（`span`/`document`，见需求文档 §4.5"锚定类型"）、`evidence`（含
`doc_ref.file_name` + `doc_ref.content_hash_sha256` + `span` 原文摘录）、可选
`messages`（多轮历史）、`status`。10 条 case 覆盖全部 4 篇 Demo 文档，其中 1 条
为图片文档级锚定（对应 pdf 内嵌示意图，命中判定见需求文档 §4.5"锚定类型"：不做
文本重叠计算，判定 Top-K 中是否出现该图片衍生的分片）。

`span` 字段均为对应文档的**原文精确子串**（已用生成后的实际文件校验，`content_hash_sha256`
同样按最终文件字节流计算）；比对时按需求文档 §4.6 的口径做空白归一化（pdf 分行渲染会在文本
抽取时插入换行，属正常现象，不影响归一化后的匹配）。

**⚠️ 本期（M3）只分发，不提供导入入口**：`eval-cases.json` 随 kb-rag-deploy 分发，
但"示例评测集导入"功能排期在 **M4b**（评测能力就绪后落地，见需求文档 §10-M4b、
`docs/M3-CONTRACTS.md` §0 第 2 点）。导入时按 `doc_ref.file_name` +
`doc_ref.content_hash_sha256` 关联 `manifest.json` 中的文档，映射为导入后实际生成
的 `doc_id`（需求文档 §5"开箱即用素材"实现机制）。

## 重新生成 docx / pdf / xlsx

```bash
cd demo/tools
pip install -r requirements.txt
python3 generate_demo_docs.py
```

脚本会覆盖写入 `demo/docs/02-document-parsing-and-chunking.docx`、
`03-hybrid-retrieval-and-rerank.pdf`、`04-eval-metrics-comparison.xlsx`。
`03-hybrid-retrieval-and-rerank.pdf` 内嵌的流水线示意图由脚本用 Pillow 现场
绘制（自造图片，非截图），优先使用系统 CJK 字体渲染中文标签，找不到则自动降级
为纯英文标签（保证脚本在没有中文字体的 CI 环境也能跑通，见脚本内
`_find_cjk_font` 注释）。

**脚本对未改动内容的重新生成是字节级可复现的**：docx/xlsx/pdf 容器本身会各自
内嵌一份"创建/修改时间"，即便正文一字未改也会导致每次生成的文件哈希不同；脚本
固定了这些时间戳（`FIXED_TIMESTAMP`、reportlab `invariant=1`、`_normalize_zip_timestamps`
统一改写 docx/xlsx 内部 zip 条目时间戳），因此正文不变时重新生成得到的字节与
`demo/eval-cases.json` 里记录的 `content_hash_sha256` 完全一致（已用两次间隔执行
验证）。**只有当你真正修改了某篇文档的正文内容时**，哈希才会变化，此时必须同步
更新 `demo/eval-cases.json` 里对应的 `span` 与 `content_hash_sha256`，否则示例
评测集在 M4b 导入时会匹配失败。

## 关联文档

- [`docs/M3-CONTRACTS.md` §3.7](../docs/M3-CONTRACTS.md)：Demo 一键导入端点契约
  （`POST /api/v1/system/demo/import`、`GET /api/v1/system/demo/status`，幂等语义）
- [`../mappings/README.md`](../mappings/README.md)：聊天记录列名映射模板（同为
  M3 交付，与 Demo 数据集相互独立）
- [知识库需求文档 §5「开箱即用素材」](../docs/知识库需求文档.md)
