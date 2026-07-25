# kb-rag-parser

kb-rag 知识库项目的 Python 文档解析微服务（M1 版本）。只负责"文件解析"这一件事：把上传的文档转成结构化的 `markdown` + 按页文本，不做任何模型调用（VLM/嵌入/切分等由 kb-rag-server 侧负责，见需求文档 §4.2 服务职责边界）。

## 技术栈

- Python 3.11+
- FastAPI + Uvicorn
- PyMuPDF（pdf）/ python-docx（docx）/ openpyxl（xlsx）/ 标准库 csv（csv）

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

成功响应 `data` 结构：

```json
{
  "markdown": "整篇文档的 markdown 表示",
  "pages": [{"page_no": 1, "text": "该页/该逻辑分段的纯文本"}],
  "images": []
}
```

`images` 在 M1 固定为空数组：图片的 OCR/VLM 文本代理生成属于 kb-rag-server 侧职责（§4.2），本服务不做模型调用。

### 支持格式一览

| file_ext | 解析库 | 说明 |
|---|---|---|
| `pdf` | PyMuPDF (`pymupdf`/`fitz`) | 按页抽取文本层；扫描件 OCR 不在 M1 范围 |
| `docx` | python-docx | 按文档原始顺序抽取段落+表格；docx 无可靠页码概念，整篇作为 `page_no=1` 返回 |
| `txt` | 标准库 | 尽力探测编码（utf-8-sig/utf-8/gbk），原样返回 |
| `md` | 标准库 | 原样透传，本身已是 markdown |
| `xlsx` | openpyxl | 每个 sheet 对应一个 `page_no`，同时渲染为 markdown 表格 |
| `csv` | 标准库 `csv` | 自动探测分隔符（逗号/分号/tab），渲染为 markdown 表格 |

新增格式只需在 `app/parsers/` 下新增一个 `BaseParser` 实现，并在 `app/parsers/registry.py` 里注册一行 —— 策略注册表模式，主流程 `app/main.py` 无需改动。

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
  main.py            FastAPI 应用与 /health、/api/v1/parse 两个端点
  models.py           pydantic 请求/响应模型
  config.py           全部常量集中定义（避免魔法值）
  errors.py           错误码与解析异常类型
  security.py         zip 安全预检 + 文件大小校验 + XML 防护加固
  encoding.py         文本编码探测（txt/md/csv 共用）
  parsers/
    base.py           BaseParser 策略接口
    registry.py       file_ext -> parser 的策略注册表
    pdf.py            pdf 解析（pymupdf）
    docx.py           docx 解析（python-docx）
    text.py           txt/md 解析
    excel.py          xlsx（openpyxl）/ csv（标准库）解析
tests/                pytest 用例：每种格式一个最小样例（代码生成），
                      以及 zip-slip / zip 炸弹负例
```

## 与 M1 契约的偏离说明

- docx 页码：Word 文档在渲染前没有可靠的分页信息，M1 将整篇文档作为单一 `page_no=1` 返回；如需真实分页，需引入排版引擎，留作后续 TODO。
- `images` 字段在 M1 恒为空数组，符合契约给出的响应示例；图片抽取与 OCR/VLM 文本代理属于后续里程碑范围。
