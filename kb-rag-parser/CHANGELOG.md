# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

## [未发布] - M14

### 修复

- **pdf 乱码页不再入库**（M3-CONTRACTS.md §2.1 乱码页判定）：内嵌子集字体缺失/损坏 ToUnicode CMap 的 pdf，`page.get_text()` 抽出的是错码位"字形汤"（中文变缅甸文/方块，数字与英文往往仍正常），文本长度达标故躲过扫描页阈值，垃圾文本被直接切分入库。现按可识别字符（ASCII/CJK/假名/中日标点等区段）占比判定：低于 `GARBLED_PAGE_VALID_CHAR_RATIO_PCT`（新环境变量，默认 50）即置空该页文本并复用扫描页路径（`scanned=true` + `page_render` 渲染交 OCR/VLM 兜底），同时在 `data.warnings[]` 记录一条说明，不失败整篇。
- pytest 新增（`tests/test_parse_pdf_garbled.py`）：乱码判定正例、正常中英文与空输入的负例、乱码页端到端降级为 `page_render` 且文本置空并带 warning。

## [未发布] - M12

### 新增

- `POST /api/v1/parse` 的 `file_ext` 扩展至 `html`/`htm`（M12-CONTRACTS.md §2，通用网页解析通道，与 M8 的聊天记录 HTML 适配器无关）：`app/parsers/html.py` 仅用标准库 `html.parser`（不引入 bs4/markdownify），事件式提取为 markdown——`<title>` 提为 `# 标题`，`h1..h6` 映射 markdown 标题前缀，块级标签切段、内联标签并入所在段；`script`/`style`/`noscript`/`template` 内容整体丢弃；固定单页、无图片产出。解析全程**零网络 I/O**（外部图片/脚本/样式一律不拉取），URL 导入的 SSRF 面全部收敛在 kb-rag-server 的抓取器侧。
- pytest 新增（`tests/test_parse_html.py`）：标题/小标题/段落提取、script/style 丢弃、实体解码、空白归并、破损标记容错、htm 后缀注册等。

## [未发布] - M8

### 新增

- `POST /api/v1/parse/chat` 的 `file_ext` 扩展至 `txt`/`html`（M8-CONTRACTS.md §0.1/§0.2，csv/xlsx 列名映射不变）：
  - **TXT 行模式**（`app/chat/txt_adapter.py`）：逐行匹配 mapping profile `txt:` 段的有序行首正则列表（命名捕获组 `send_time`/`sender`，可选 `content`），内置 `liuhen`（`YYYY-MM-DD HH:MM:SS 发送人` 换行消息体）与 `wechat_pc`（`发送人 (时间):` 同行/换行消息体）两种模板；不匹配任何模板的行归并为上一条消息的续行；不匹配行占比 > 30%（分母只计非空行，分子不含已开始消息的续行）判定为格式/模板选错，直接返回 `PARSE_FAILED` 并给出可操作提示。固定解析为单一 `ChatSession`（`session_id`/`session_name` 取文件名 stem）。
  - **HTML DOM 选择器**（`app/chat/html_adapter.py`）：仅用标准库 `html.parser`（不引入 bs4），解析前剥离 `<script>`/`<style>` 内容，绝不请求任何远程资源（`<img src>` 只判断是否存在，从不下载）。消息节点/发送人/时间/正文由 mapping profile `html:` 段的选择器定位（`tag`/`.class`/`#id`/`tag.class` 最小选择器子集），内置"留痕"模板；图片消息节点转固定 `content="[IMAGE]"` 占位（`msg_type=image`），语音/视频消息整条跳过并计入 `skipped`（与 csv/xlsx 语义一致）；`message` 选择器零匹配判定为选择器/模板选错，返回 `PARSE_FAILED`。同样固定解析为单一 `ChatSession`。
- `profile_yaml` 请求内联映射档案（`multipart/form-data` 可选字段，M8-CONTRACTS.md §0.7）：若提供则整份 YAML 优先于 `mapping_profile` 对应的本地文件生效，本地 `app/mappings/*.yml` 仅作为种子/默认内容；`mapping_profile` 省略时按 `file_ext` 取内置默认（csv/xlsx -> `memotrace`，txt -> `liuhen_txt`，html -> `liuhen_html`）。
- **PaddleOCR 本地 OCR 兜底**（`app/ocr/engine.py`，M8-CONTRACTS.md §0.4）：新环境变量 `OCR_ENGINE`（`none`\|`paddle`，默认 `none`）与 `OCR_TIMEOUT_S`（默认 30 秒）。扫描页 OCR 三级次序：kb-rag-server 侧 VLM（有 Key，现状）→ 本服务 PaddleOCR 兜底（`OCR_ENGINE=paddle` 且已装可选依赖，离线/零 Key）→ 跳过并降级（现状）。`OCR_ENGINE=paddle` 但未安装 `requirements-ocr.txt` 时，应用启动即 fast-fail 报可操作错误，不拖到首次遇到扫描页才失败。本服务 OCR 成功识别的扫描页，`pages[].text` 回填为 OCR 结果，`pages[].ocr_source` 置为 `"paddle"`；kb-rag-server 对带 `ocr_source` 的页不再调用 VLM。单页 OCR 超时/异常按页跳过并计数，不影响整篇文档。
- 新增可选依赖文件 `requirements-ocr.txt`：`paddlepaddle==3.3.1` + `paddleocr==3.3.3`（CPU 推理，`ch_PP-OCRv4` 中英文模型），默认不随 `requirements.txt`/Dockerfile 基础镜像安装，仅在需要 `OCR_ENGINE=paddle` 时显式 `pip install -r requirements-ocr.txt`。
- `app/mappings/` 新增内置映射档案：`liuhen_txt.yml`（TXT 行模板：`liuhen` + `wechat_pc`）、`liuhen_html.yml`（HTML DOM 选择器：留痕导出模板）；连同既有 `memotrace.yml` 构成三份内置模板。
- pytest 新增：TXT 双模板正例、自定义正则覆盖、30% 不匹配失败线、多行消息体归并；HTML 留痕模板正例、script/style 安全剥离、图片/语音/视频消息语义、选择器零匹配失败；`profile_yaml` 优先于本地 `mapping_profile` 文件；`OCR_ENGINE` 三态（`none` 与现状一致、`paddle` 未装依赖启动 fast-fail、装依赖后真实推理产出 `ocr_source=paddle`——真实 PaddleOCR 推理用例按依赖是否安装 `skipif` 自动跳过）。

### 已知限制 / 待校准

- `liuhen_txt`/`liuhen_html` 映射档案与 `memotrace` 同样依据公开约定（留痕/MemoTrace、微信 PC 端导出格式的公开资料）编写，尚未用真实导出样例校准，详见各 yml 文件顶部说明。
- TXT/HTML 固定解析为单一 `ChatSession`，不支持同文件多会话拆分；csv/xlsx 仍支持多会话（多房间）。真实样例若显示需要多会话拆分（如按分隔符/标题行区分），届时再补。
- `requirements-ocr.txt` 的版本选型（`paddlepaddle==3.3.1`/`paddleocr==3.3.3`）是在 macOS arm64 + Python 3.13 环境实测校准的结果（原拟 `paddlepaddle==2.6.2`/`paddleocr==2.7.3` 在该平台无可用 wheel），并已适配 PaddleOCR 3.x API；部署到实际目标环境前建议重新验证一次安装与推理。

## [0.2.0] - M3

### 新增

- `POST /api/v1/parse` 响应扩展（向后兼容，M1 字段不变）：
  - `pages[]` 增 `scanned` 布尔（该页文本去空白后长度 < `SCANNED_PAGE_TEXT_THRESHOLD`，默认 20，判定为扫描页）。
  - 扫描页按 150dpi 渲染为 PNG（`kind=page_render`），非扫描页抽取 pdf/docx 内嵌图片（`kind=embedded`）；两者都在 `markdown` 中以独占一行的 `[[IMAGE:{image_id}]]` 占位符标记位置，图片二进制以 base64 存于 `images[]`。
  - `data.warnings[]`：单文档图片数上限（默认 100，`MAX_IMAGES_PER_DOC`）、单图字节上限（默认 10MB，`MAX_IMAGE_BYTES`）超限时跳过该图并记录 warning，不失败整篇。
- `POST /api/v1/parse/chat`：新端点，解析聊天记录导出（csv/xlsx）为 `sessions[].messages[]` + `skipped` 统计。列名映射从 `app/mappings/*.yml` 载入（内置 `memotrace` 一份），大小写/空格容错；`send_time` 自动判别 epoch 秒/毫秒/常见字符串格式；语音/视频消息跳过并计入 `skipped`，图片消息保留 `msg_type=image`。
- `app/parsers/images.py`：pdf/docx 共用的图片采集与上限保护（`ImageAssetCollector`）。
- `app/chat/`：聊天记录解析包（`mapping.py` 列名映射、`normalize.py` 时间/类型归一化、`parser.py` csv/xlsx 编排）。
- 新增依赖 `PyYAML==6.0.3`（映射档案 yml 解析）。
- pytest 新增：扫描页判定与渲染、占位符与 `images[]` 一致性、图片数/字节上限保护、docx 内嵌图片抽取、chat csv/xlsx 正例、列名缺失负例、时间格式多态（秒/毫秒/字符串）、语音视频跳过统计。

### 已知限制 / 待校准

- `memotrace` 映射档案与 WeChat 数值 `msg_type` 编码依据公开约定编写，尚未用真实 MemoTrace 导出样例校准（M3-CONTRACTS.md §2.2 已标注此风险）。
- docx 内嵌图片的占位符位置：能匹配到 `r:embed` 引用的段落时按原文顺序插入；无法匹配到具体段落的图片（如表格单元格、页眉页脚内的图片）统一追加在文档末尾。

## [0.1.0] - M1

### 新增

- `POST /api/v1/parse`：支持 pdf（pymupdf）/ docx（python-docx）/ txt / md / xlsx（openpyxl）/ csv 六种格式，统一返回 `{markdown, pages, images}`。
- `GET /health` 存活探针。
- 解析器策略注册表（`app/parsers/`），新增格式只需新增一个 `BaseParser` 实现并注册。
- 解析安全约束落地（需求文档 §4.2）：
  - 全局启用 `defusedxml.defuse_stdlib()` 防 XXE，并审计确认 python-docx / openpyxl 自身的 lxml 解析器已禁用实体解析。
  - docx/xlsx 解析前的 zip 安全预检：zip-slip 路径越界校验、解压总大小上限 500MB、条目数上限 2000。
  - 上传文件大小上限 100MB。
  - 解析调用 300s 超时熔断。
  - 全程未实现任何出站网络请求。
- pytest 用例：每种格式一个最小样例（代码生成，无二进制 fixture），以及 zip-slip / zip 炸弹负例。
