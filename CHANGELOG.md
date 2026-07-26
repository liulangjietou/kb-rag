# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

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
