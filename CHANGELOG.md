# Changelog

本项目的版本记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 约定。

## [Unreleased]

### Added

- M1 基础框架：Vite + React 18 + TypeScript + Ant Design 5 + react-router v6 + axios。
- 登录页与首登强制改密流程（`must_change_password`）。
- 知识库列表页：新建、删除（二次确认）、空态引导。
- 知识库详情页：文档拖拽上传、文档列表（`process_status` 状态标签 + 3s 轮询）、失败原因展示、重建按钮、分片抽屉（分页查看 chunk 内容与 metadata）。
- 检索调试页：知识库选择、`recall_top_k`/`top_n` 参数、结果卡片展示 `score`/`score_type`/`retrieval_source`，`degraded` 非空时的顶部告警。
- 系统设置占位页：只读展示嵌入模型配置状态。
- 全局零 Key 提示 banner（`GET /api/v1/system/model-status`）。
- axios 统一拦截器：Bearer token 注入、401 自动跳转登录、错误统一提示并附带 `request_id`。
