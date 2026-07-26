# kb-rag-parser

kb-rag 知识库项目的 Python 文档解析微服务（M1 基线 + M3 多模态/聊天记录增量）。只负责"文件解析"这一件事：把上传的文档转成结构化的 `markdown` + 按页文本 + 图片二进制，把聊天记录导出转成结构化会话消息；不做任何模型调用（VLM 图文理解/嵌入/切分等由 kb-rag-server 侧负责，见需求文档 §4.2 服务职责边界、M3-CONTRACTS.md §0）。

## 技术栈

- Python 3.11+
- FastAPI + Uvicorn
- PyMuPDF（pdf）/ python-docx（docx）/ openpyxl（xlsx）/ 标准库 csv（csv）/ PyYAML（聊天列名映射档案）

## 快速启动

```bash
# 1. 创建虚拟环境并安装依赖
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 2. 启动服务（默认端口 20001，与 M1 契约 kb-rag-parser 端口一致）
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 20001

# 3. 运行测试
.venv/bin/pytest -q
```

## API 说明

统一响应体（与 kb-rag-server 一致的契约风格）：

```json
{"code": "OK", "data": {...}, "message": "success", "request_id": "..."}
```

失败时 `code` 为 `PARSE_FAILED`，`data` 为 `null`，`message` 给出可读的失败原因（不泄露原始堆栈）。

### `GET /health`

存活探针。

```json
{"status": "UP"}
```

### `POST /api/v1/parse`

`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | 待解析的文档 |
| `file_ext` | form 字段 | 文件扩展名（不带点），如 `pdf`/`docx`/`txt`/`md`/`xlsx`/`csv` |

成功响应 `data` 结构（M3-CONTRACTS.md §2.1，向后兼容 M1）：

```json
{
  "markdown": "整篇文档的 markdown 表示，可能含独占一行的 [[IMAGE:img_1]] 占位符",
  "pages": [{"page_no": 1, "text": "该页/该逻辑分段的纯文本", "scanned": false}],
  "images": [
    {"image_id": "img_1", "page_no": 1, "kind": "embedded", "media_type": "image/png", "content_base64": "..."}
  ],
  "warnings": []
}
```

- `pages[].scanned`：该页文本去空白后长度 < `SCANNED_PAGE_TEXT_THRESHOLD`（默认 20，环境变量可配）即为 `true`，此时该页按 150dpi 渲染为 PNG（`kind=page_render`）替代文本层；只有 pdf 页面可能为扫描页，其余格式恒为 `false`。
- `images[].kind`：`embedded`（pdf/docx 内嵌图片）或 `page_render`（扫描页整页渲染）；图片的 OCR/VLM 文本代理生成仍属于 kb-rag-server 侧职责（§4.2），本服务只产出图片二进制，不做模型调用。
- `warnings[]`：单文档图片数上限（默认 100，`MAX_IMAGES_PER_DOC`）或单图字节上限（默认 10MB，`MAX_IMAGE_BYTES`）超限时，该图被跳过并在此记录说明，不影响文档其余部分正常返回。

### `POST /api/v1/parse/chat`

聊天记录导出（csv/xlsx）解析为结构化会话消息（M3-CONTRACTS.md §2.2）。`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | 待解析的聊天记录导出文件 |
| `file_ext` | form 字段 | `csv` 或 `xlsx` |
| `mapping_profile` | form 字段，可选 | 列名映射档案名，默认 `memotrace`，对应 `app/mappings/{profile}.yml` |

成功响应 `data` 结构：

```json
{
  "sessions": [{
    "session_id": "room_a", "session_name": "Alice's Room",
    "messages": [{"msg_id": "...", "sender": "alice", "is_self": false, "send_time": 1737800000000, "msg_type": "text", "content": "..."}]
  }],
  "skipped": {"voice": 0, "video": 0, "other": 0}
}
```

- 列名映射：从 `app/mappings/*.yml` 载入，按目标字段的候选列名列表依次匹配，匹配时忽略大小写与空白；只有 `content` 是硬性要求（一处 fast-fail 边界），其余字段（`session_id`/`sender`/`is_self`/`send_time`/`msg_type`）在列缺失时优雅降级（如落入默认会话、`sender` 置空、`msg_type` 默认为 `text`）。新增来源只需在 `app/mappings/` 下新增一份 yml，无需改代码。
- `send_time` 归一化：自动判别 epoch 秒/毫秒（以 10^11 为分界，见 `app/chat/normalize.py`）、xlsx 原生日期单元格、常见字符串格式；单条消息无法解析时该消息计入 `skipped.other`（记 info 日志），不影响其余消息。
- 语音/视频消息（`msg_type` 判定为 `voice`/`video`）从 `sessions[].messages[]` 中剔除并计入 `skipped`；图片消息保留（`msg_type=image`，`content` 为导出文件中的原始占位描述文本，本服务不下载聊天内嵌图片）。
- **⚠️ 内置 `memotrace` 档案依据公开列名/编码约定编写，尚未用真实导出样例校准**，详见 `app/mappings/memotrace.yml` 顶部说明。

### 支持格式一览

| file_ext | 解析库 | 说明 |
|---|---|---|
| `pdf` | PyMuPDF (`pymupdf`/`fitz`) | 按页抽取文本层；无文本层的页面（扫描页）渲染为 PNG，OCR 由 kb-rag-server 侧 VLM 承担（M3-CONTRACTS.md §0），本服务不引入 PaddleOCR |
| `docx` | python-docx | 按文档原始顺序抽取段落+表格+内嵌图片；docx 无可靠页码概念，整篇作为 `page_no=1` 返回 |
| `txt` | 标准库 | 尽力探测编码（utf-8-sig/utf-8/gbk），原样返回 |
| `md` | 标准库 | 原样透传，本身已是 markdown |
| `xlsx` | openpyxl | 每个 sheet 对应一个 `page_no`，同时渲染为 markdown 表格 |
| `csv` | 标准库 `csv` | 自动探测分隔符（逗号/分号/tab），渲染为 markdown 表格 |

新增文档格式只需在 `app/parsers/` 下新增一个 `BaseParser` 实现，并在 `app/parsers/registry.py` 里注册一行 —— 策略注册表模式，主流程 `app/main.py` 无需改动。

## 安全约束（需求文档 §4.2 落实情况）

| 约束 | 落实方式 |
|---|---|
| 禁止出站网络请求 | 全程未实现任何 HTTP 客户端 / URL 拉取逻辑，杜绝 SSRF |
| XML 解析防 XXE | 应用启动时调用 `defusedxml.defuse_stdlib()` 全局加固标准库 XML；另外审计确认 docx/xlsx 实际使用的 lxml 解析器均已配置 `resolve_entities=False`（python-docx 与 openpyxl 在其安装版本中均自带此项默认防护），详见 `app/security.py` 模块 docstring |
| zip 安全预检（防 zip-slip / zip 炸弹） | docx/xlsx 本质是 zip 包，解析前先经 `ensure_zip_is_safe` 校验：条目路径不得越出（zip-slip）、解压总大小上限 500MB、条目数上限 2000，超限 fast-fail 返回 `PARSE_FAILED` |
| 单文件大小上限 | 100MB，在进入解析前校验，超限 fast-fail |
| 解析超时 | 300s，通过 `asyncio.wait_for` 包裹在线程池中执行的解析调用；超时同样返回 `PARSE_FAILED` |

## 项目结构

```
app/
  main.py            FastAPI 应用与 /health、/api/v1/parse、/api/v1/parse/chat 三个端点
  models.py           pydantic 请求/响应模型
  config.py           全部常量集中定义（避免魔法值），含 M3 环境变量读取
  errors.py           错误码与解析异常类型
  security.py         zip 安全预检 + 文件大小校验 + XML 防护加固
  encoding.py         文本编码探测（txt/md/csv 共用）
  parsers/
    base.py           BaseParser 策略接口
    registry.py       file_ext -> parser 的策略注册表
    pdf.py            pdf 解析（pymupdf）：文本层 + 扫描页渲染 + 内嵌图片
    docx.py           docx 解析（python-docx）：段落/表格 + 内嵌图片
    text.py           txt/md 解析
    excel.py          xlsx（openpyxl）/ csv（标准库）解析
    images.py         pdf/docx 共用的图片采集与数量/字节上限保护
  chat/
    mapping.py         聊天列名映射档案加载与解析（app/mappings/*.yml）
    normalize.py        is_self/send_time/msg_type 归一化
    parser.py           chat csv/xlsx 解析编排
  mappings/
    memotrace.yml       内置 MemoTrace 列名映射档案（尚待真实样例校准）
tests/                pytest 用例：每种文档格式一个最小样例（代码生成）、
                      zip-slip / zip 炸弹负例、M3 扫描页/内嵌图片/上限保护、
                      chat csv/xlsx 正例与负例
```

## 与契约的偏离说明

M1：

- docx 页码：Word 文档在渲染前没有可靠的分页信息，整篇文档作为单一 `page_no=1` 返回（M3 内嵌图片沿用同一 `page_no=1`）；如需真实分页，需引入排版引擎，留作后续 TODO。

M3（M3-CONTRACTS.md §2）：

- 扫描页判定为 `true` 时，不再额外抽取该页的内嵌图片（整页已作为一张 `page_render` 图片产出，避免同一内容被计两次）。
- docx 内嵌图片的占位符位置：能从段落 XML 匹配到 `r:embed` 引用时按原文顺序在该段落后插入占位符；无法匹配到具体段落的图片（如表格单元格、页眉页脚内的图片）统一追加在文档末尾，仍计入 `images[]`，不会丢失。
- `memotrace` 映射档案与 WeChat 数值 `msg_type` 编码依据公开约定编写，尚未用真实 MemoTrace 导出样例校准（契约本身也标注了这一点）；`content` 列是唯一的硬性 fast-fail 条件，其余字段缺失时优雅降级而非整篇失败。
