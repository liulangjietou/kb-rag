# kb-rag-parser

kb-rag 知识库项目的 Python 文档解析微服务（M1 基线 + M3 多模态/聊天记录增量 + M8 导入与解析增强增量）。只负责"文件解析"这一件事：把上传的文档转成结构化的 `markdown` + 按页文本 + 图片二进制，把聊天记录导出（csv/xlsx/txt/html）转成结构化会话消息；不做任何模型调用（VLM 图文理解/嵌入/切分等由 kb-rag-server 侧负责，见需求文档 §4.2 服务职责边界、M3-CONTRACTS.md §0），扫描页 OCR 的 PaddleOCR 兜底（M8-CONTRACTS.md §0.4）是唯一例外，且默认关闭。

## 技术栈

- Python 3.11+
- FastAPI + Uvicorn
- PyMuPDF（pdf）/ python-docx（docx）/ openpyxl（xlsx）/ 标准库 csv（csv）/ 标准库 html.parser（html 聊天记录）/ PyYAML（聊天列名映射/行模板/DOM 选择器档案）
- 可选：PaddleOCR（`requirements-ocr.txt`，`OCR_ENGINE=paddle` 时才需要，默认不装）

## 许可注意

本项目以 Apache-2.0 发布（见 `LICENSE`），但 `requirements.txt` 中的 pdf 解析依赖
**PyMuPDF（1.28.0）实际许可为 GNU AGPL-3.0**（Artifex 双重许可：AGPL-3.0 或付费的 Artifex
商业许可，本项目使用的是免费的 AGPL-3.0 分发；许可信息以 `pip show pymupdf` 与 PyPI 官方元数据
为准，完整核实记录见 `NOTICE`）。`app/parsers/pdf.py` 直接依赖 `pymupdf`/`fitz`，是 pdf 解析
路径不可或缺的一环，而非可隔离的子进程或独立服务。

AGPL-3.0 与本项目 Apache-2.0 的整体许可目标之间存在**尚未解决**的合规关系：AGPL-3.0 要求，若
包含 AGPL-3.0 代码的程序以网络服务形式对外提供，须向该服务的使用者提供该程序（含本服务自身代码
及所有修改）的完整对应源代码。这是否可接受、如何处理，是**项目 Owner 需要做出的决策**，本文档
不代为下结论、也不淡化这一事实。可能的方向包括（不分先后、不隐含推荐）：

1. 接受本服务（kb-rag-parser）组件按 AGPL-3.0 义务分发/运营，kb-rag 项目其余部分仍保持
   Apache-2.0；
2. 更换 pdf 解析库为许可更宽松的替代方案（如基于 PDFium 的 `pypdfium2`、基于 pdfminer.six 的
   `pdfplumber` 等），代价是需要重新验证解析行为与质量；
3. 向 Artifex 购买 PyMuPDF 商业许可，以消除 AGPL-3.0 的网络服务义务。

在项目 Owner 做出决策前，请按实际承担 AGPL-3.0 义务对待本服务的分发与部署。

## 快速启动

```bash
# 1. 创建虚拟环境并安装依赖
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 1.1 可选：启用 PaddleOCR 扫描页兜底时再装（默认不装，保持镜像精简）
.venv/bin/pip install -r requirements-ocr.txt

# 2. 启动服务（默认端口 20001，与 M1 契约 kb-rag-parser 端口一致）
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 20001

# 3. 运行测试
.venv/bin/pytest -q
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SCANNED_PAGE_TEXT_THRESHOLD` | `20` | pdf 页面文本层短于此长度即判定为扫描页 |
| `MAX_IMAGES_PER_DOC` | `100` | 单文档图片数量上限 |
| `MAX_IMAGE_BYTES` | `10485760`（10MB） | 单张图片字节数上限 |
| `OCR_ENGINE` | `none` | `none`\|`paddle`；`paddle` 但未装 `requirements-ocr.txt` 时启动 fast-fail（M8-CONTRACTS.md §0.4） |
| `OCR_TIMEOUT_S` | `30` | 单页 OCR 超时（秒），超时按页跳过并计数，不影响整篇文档 |

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
- `pages[].ocr_source`（M8-CONTRACTS.md §0.4）：扫描页被本服务 PaddleOCR 兜底成功识别出文本时为 `"paddle"`（此时 `pages[].text` 已回填为 OCR 结果），否则为 `null`（`OCR_ENGINE=none` 默认状态 / 非扫描页 / OCR 超时或失败按页跳过）。kb-rag-server 对带 `ocr_source` 的页不应再走自己的 VLM。
- `images[].kind`：`embedded`（pdf/docx 内嵌图片）或 `page_render`（扫描页整页渲染）；图片的 VLM 文本代理生成仍属于 kb-rag-server 侧职责（§4.2），本服务默认不做任何模型调用，`ocr_source` 是唯一例外且默认关闭。
- `warnings[]`：单文档图片数上限（默认 100，`MAX_IMAGES_PER_DOC`）或单图字节上限（默认 10MB，`MAX_IMAGE_BYTES`）超限时，该图被跳过并在此记录说明，不影响文档其余部分正常返回。

### `POST /api/v1/parse/chat`

聊天记录导出解析为结构化会话消息（M3-CONTRACTS.md §2.2，file_ext 由 M8-CONTRACTS.md §0.1/§0.2 扩至 txt/html）。`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | 待解析的聊天记录导出文件 |
| `file_ext` | form 字段 | `csv`\|`xlsx`\|`txt`\|`html` |
| `mapping_profile` | form 字段，可选 | 映射档案名，对应 `app/mappings/{profile}.yml`；省略时按 `file_ext` 取内置默认（csv/xlsx -> `memotrace`，txt -> `liuhen_txt`，html -> `liuhen_html`） |
| `profile_yaml` | form 字段，可选 | 映射档案的完整 YAML 文本；**若提供则优先于 `mapping_profile` 对应的本地文件**（本地 yml 仅作为种子/默认内容，M8-CONTRACTS.md §0.7） |

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

csv/xlsx（列名映射，未变）：

- 从 `app/mappings/*.yml` 载入，按目标字段的候选列名列表依次匹配，匹配时忽略大小写与空白；只有 `content` 是硬性要求（一处 fast-fail 边界），其余字段（`session_id`/`sender`/`is_self`/`send_time`/`msg_type`）在列缺失时优雅降级（如落入默认会话、`sender` 置空、`msg_type` 默认为 `text`）。新增来源只需在 `app/mappings/` 下新增一份 yml，无需改代码。
- `send_time` 归一化：自动判别 epoch 秒/毫秒（以 10^11 为分界，见 `app/chat/normalize.py`）、xlsx 原生日期单元格、常见字符串格式；单条消息无法解析时该消息计入 `skipped.other`（记 info 日志），不影响其余消息。
- 语音/视频消息（`msg_type` 判定为 `voice`/`video`）从 `sessions[].messages[]` 中剔除并计入 `skipped`；图片消息保留（`msg_type=image`，`content` 为导出文件中的原始占位描述文本，本服务不下载聊天内嵌图片）。
- **⚠️ 内置 `memotrace` 档案依据公开列名/编码约定编写，尚未用真实导出样例校准**，详见 `app/mappings/memotrace.yml` 顶部说明。

txt（M8-CONTRACTS.md §0.1，行模式）：

- 逐行匹配 mapping profile `txt:` 段的有序行首正则列表（命名捕获组 `send_time`/`sender`，可选 `content`），内置 `liuhen`（`YYYY-MM-DD HH:MM:SS 发送人` 换行消息体）与 `wechat_pc`（`发送人 (时间):` 同行/换行消息体）两种模板，见 `app/mappings/liuhen_txt.yml`；不匹配任何模板的行归并为上一条消息的续行，若发生在首条消息之前则计入"不匹配"。
- 整个文件的不匹配行占比 > 30% 判定为格式/模板选错，直接返回 `PARSE_FAILED` 并给出可操作提示，而非静默产出残缺会话。
- 一个 txt 文件固定解析为单一 `ChatSession`（`session_id`/`session_name` 取自文件名 stem）。
- 可通过 `profile_yaml`/自定义 `mapping_profile` 的 `txt:` 段整体替换为自定义正则。

html（M8-CONTRACTS.md §0.2，DOM 选择器）：

- 仅用标准库 `html.parser`（不引入 bs4），解析前剥离 `<script>`/`<style>` 内容、绝不请求任何远程资源（`<img src>` 只判断"是否存在"，从不下载）。
- 消息节点/发送人/时间/正文由 mapping profile `html:` 段的选择器定位（`tag`/`.class`/`#id`/`tag.class` 的最小选择器子集），内置"留痕"模板见 `app/mappings/liuhen_html.yml`。
- 图片消息节点（命中 `image` 选择器）转为固定 `content="[IMAGE]"` 占位、`msg_type=image`；命中 `voice`/`video` 选择器的消息整条跳过并计入 `skipped`，与 csv/xlsx 语义一致。
- `message` 选择器一个节点都没匹配到时判定为选择器/模板选错，返回 `PARSE_FAILED`。
- 同样固定解析为单一 `ChatSession`；可通过 `profile_yaml`/自定义 `mapping_profile` 的 `html:` 段整体替换选择器。

### 支持格式一览

`/api/v1/parse`：

| file_ext | 解析库 | 说明 |
|---|---|---|
| `pdf` | PyMuPDF (`pymupdf`/`fitz`) | 按页抽取文本层；无文本层的页面（扫描页）渲染为 PNG，默认交由 kb-rag-server 侧 VLM OCR（M3-CONTRACTS.md §0），`OCR_ENGINE=paddle` 时本服务自行用 PaddleOCR 兜底出文本（M8-CONTRACTS.md §0.4） |
| `docx` | python-docx | 按文档原始顺序抽取段落+表格+内嵌图片；docx 无可靠页码概念，整篇作为 `page_no=1` 返回 |
| `txt` | 标准库 | 尽力探测编码（utf-8-sig/utf-8/gbk），原样返回 |
| `md` | 标准库 | 原样透传，本身已是 markdown |
| `xlsx` | openpyxl | 每个 sheet 对应一个 `page_no`，同时渲染为 markdown 表格 |
| `csv` | 标准库 `csv` | 自动探测分隔符（逗号/分号/tab），渲染为 markdown 表格 |

新增文档格式只需在 `app/parsers/` 下新增一个 `BaseParser` 实现，并在 `app/parsers/registry.py` 里注册一行 —— 策略注册表模式，主流程 `app/main.py` 无需改动。

`/api/v1/parse/chat`：`csv`/`xlsx`（列名映射）、`txt`（行模板，见上）、`html`（DOM 选择器，见上）。

## 安全约束（需求文档 §4.2 落实情况）

| 约束 | 落实方式 |
|---|---|
| 禁止出站网络请求 | 全程未实现任何 HTTP 客户端 / URL 拉取逻辑，杜绝 SSRF |
| XML 解析防 XXE | 应用启动时调用 `defusedxml.defuse_stdlib()` 全局加固标准库 XML；另外审计确认 docx/xlsx 实际使用的 lxml 解析器均已配置 `resolve_entities=False`（python-docx 与 openpyxl 在其安装版本中均自带此项默认防护），详见 `app/security.py` 模块 docstring |
| zip 安全预检（防 zip-slip / zip 炸弹） | docx/xlsx 本质是 zip 包，解析前先经 `ensure_zip_is_safe` 校验：条目路径不得越出（zip-slip）、解压总大小上限 500MB、条目数上限 2000，超限 fast-fail 返回 `PARSE_FAILED` |
| 单文件大小上限 | 100MB，在进入解析前校验，超限 fast-fail |
| 解析超时 | 300s，通过 `asyncio.wait_for` 包裹在线程池中执行的解析调用；超时同样返回 `PARSE_FAILED` |
| html 聊天记录安全（M8） | 仅用标准库 `html.parser`；剥离 `<script>`/`<style>` 内容，`<img>` 只判断是否存在、从不下载 |
| OCR 依赖门禁（M8） | `OCR_ENGINE=paddle` 但未 `pip install -r requirements-ocr.txt` 时，应用启动即 fast-fail（见 `app/ocr/engine.py`），不会拖到首次遇到扫描页才报错 |

## 项目结构

```
app/
  main.py            FastAPI 应用与 /health、/api/v1/parse、/api/v1/parse/chat 三个端点
  models.py           pydantic 请求/响应模型
  config.py           全部常量集中定义（避免魔法值），含 M3/M8 环境变量读取
  errors.py           错误码与解析异常类型
  security.py         zip 安全预检 + 文件大小校验 + XML 防护加固
  encoding.py         文本编码探测（txt/md/csv 共用）
  parsers/
    base.py           BaseParser 策略接口
    registry.py       file_ext -> parser 的策略注册表
    pdf.py            pdf 解析（pymupdf）：文本层 + 扫描页渲染（+ M8 PaddleOCR 兜底回填）+ 内嵌图片
    docx.py           docx 解析（python-docx）：段落/表格 + 内嵌图片
    text.py           txt/md 解析
    excel.py          xlsx（openpyxl）/ csv（标准库）解析
    images.py         pdf/docx 共用的图片采集与数量/字节上限保护
  chat/
    mapping.py         映射档案加载：csv/xlsx 列名候选 + M8 txt 行模板/html 选择器（app/mappings/*.yml 或随请求传入的 profile_yaml）
    normalize.py        is_self/send_time/msg_type 归一化
    parser.py           chat csv/xlsx/txt/html 解析编排（按 file_ext 分派）
    txt_adapter.py       M8 txt 行模式适配器：多行归并 + 30% 不匹配行失败线
    html_adapter.py      M8 html DOM 适配器：最小选择器引擎 + script/style 剥离
  mappings/
    memotrace.yml       内置 MemoTrace 列名映射档案（尚待真实样例校准）
    liuhen_txt.yml      内置 txt 行模板档案（liuhen + wechat_pc，尚待真实样例校准）
    liuhen_html.yml      内置 html DOM 选择器档案（尚待真实样例校准）
  ocr/
    engine.py            M8 PaddleOCR 兜底：OCR_ENGINE 开关、启动 fast-fail、单页超时降级
tests/                pytest 用例：每种文档格式一个最小样例（代码生成）、
                      zip-slip / zip 炸弹负例、M3 扫描页/内嵌图片/上限保护、
                      chat csv/xlsx/txt/html 正例与负例、profile_yaml 优先级、
                      OCR_ENGINE 三态（真实 PaddleOCR 推理按依赖是否安装 skip）
```

## 与契约的偏离说明

M1：

- docx 页码：Word 文档在渲染前没有可靠的分页信息，整篇文档作为单一 `page_no=1` 返回（M3 内嵌图片沿用同一 `page_no=1`）；如需真实分页，需引入排版引擎，留作后续 TODO。

M3（M3-CONTRACTS.md §2）：

- 扫描页判定为 `true` 时，不再额外抽取该页的内嵌图片（整页已作为一张 `page_render` 图片产出，避免同一内容被计两次）。
- docx 内嵌图片的占位符位置：能从段落 XML 匹配到 `r:embed` 引用时按原文顺序在该段落后插入占位符；无法匹配到具体段落的图片（如表格单元格、页眉页脚内的图片）统一追加在文档末尾，仍计入 `images[]`，不会丢失。
- `memotrace` 映射档案与 WeChat 数值 `msg_type` 编码依据公开约定编写，尚未用真实 MemoTrace 导出样例校准（契约本身也标注了这一点）；`content` 列是唯一的硬性 fast-fail 条件，其余字段缺失时优雅降级而非整篇失败。

M8（M8-CONTRACTS.md §0.1/§0.2/§0.3/§0.4，均待真实样例到位后校准，见各 yml 顶部说明）：

- txt 不匹配行占比的分母只统计非空行，且仅把"当前尚无任何消息已开始时的不匹配行"计入分子；已开始的消息体续行（多行归并的正常情形）不计入分子。这是为了让"多行消息体"这一正常情形不误触发 30% 失败线，同时仍能在真正文不对题的文件上正确触发（详见 `app/chat/txt_adapter.py` 模块 docstring）。
- html/txt 一个文件固定产出单一 `ChatSession`（`session_id`/`session_name` 取文件名 stem），不像 csv/xlsx 支持同文件多会话（多房间）——txt/html 导出本身通常就是单个会话的逐条转储，真实样例若显示需要多会话拆分（如按分隔符/标题行），届时再补。
- html `message` 选择器匹配零节点、txt 无 `txt:` patterns 配置，均视为配置/格式错误直接 fast-fail（`PARSE_FAILED`），不静默返回空会话——避免误配置被当作"这个文件恰好没有消息"。
- PaddleOCR 版本选型：`requirements-ocr.txt` 固定 `paddleocr==3.3.3` + `paddlepaddle==3.3.1`（CPU）+ `ch_PP-OCRv4` 模型。原拟 `paddlepaddle==2.6.2`/`paddleocr==2.7.3` 在 macOS arm64 + Python 3.13 无可用 wheel 不可装，实测后改钉上述版本，`app/ocr/engine.py` 已适配 PaddleOCR 3.x API（`use_textline_orientation`/`predict()`/`rec_texts`，关闭文档级预处理模型——本引擎只处理已摆正的单页渲染图）。已在 M8 验收（2026-07-27）实机 `pip install -r requirements-ocr.txt` 并对扫描 PDF 页跑通真实推理（产出文本且 `ocr_source=paddle`）。本仓库默认测试环境（`requirements.txt`）不装该可选依赖，OCR 真实推理用例按依赖是否安装标记 `skipif` 自动跳过；模型默认从 HuggingFace 拉取，部分网络不可达时可设 `PADDLE_PDX_MODEL_SOURCE=ModelScope`，纯离线部署须预置模型缓存目录。

## 文档导航

本仓库只维护自身的 README/CHANGELOG 与代码内 docstring；跨仓库的架构总览、端到端业务流程与
里程碑契约集中维护在 [kb-rag-deploy](https://github.com/liulangjietou/kb-rag-deploy) 仓库：

- 整体架构（本服务在 kb-rag 系统中的定位、与 kb-rag-server 的调用关系）：
  `docs/ARCHITECTURE.md` §4（kb-rag-parser 架构）
- 端到端业务流程（文档导入、聊天记录导入等完整链路，本服务只是其中一环）：`docs/FLOWS.md`
- 各里程碑契约（本服务行为的规范来源，本仓库的实现与偏离说明均以其为准）：
  `docs/M1-CONTRACTS.md`、`docs/M3-CONTRACTS.md`、`docs/M8-CONTRACTS.md`
- 接口契约（OpenAPI）：`docs/openapi/kb-parser.yaml`
- 贡献与安全：本仓库的 `CONTRIBUTING.md` / `SECURITY.md`；跨仓库通用约定另见
  kb-rag-deploy 仓库同名文件
