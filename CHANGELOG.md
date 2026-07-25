# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

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
