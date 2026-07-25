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
- M2 检索调试页升级：参数面板按改写/召回/融合/重排/过滤/返回分组折叠（`fusion.mode`+`w_vec`滑杆+`rrf_k`、`score_threshold`、`metadata_filter` 编辑器含标签多选/会话 ID/发送人/时间范围）；结果卡片展示各路原始分/归一化分/`fused_score`/`rerank_score`（存在时）、阈值作用类型标签、父子模式命中子片明细展开；顶部 `applied` 信息条（改写后 query、融合模式、阈值作用分数）；`degraded` 新增枚举值（`query_rewrite_timeout`/`rerank_timeout`/`rerank_error`/`threshold_inactive`）的中文映射。
- 系统设置页升级：模型状态 tab 改为 embedding/rerank/chat 三卡展示；新增「ik 词典」tab（分页列表、新增词条弹窗、删除确认、启停切换）。
- 知识库详情页新增：索引配置编辑抽屉（分段长度/重叠、父子分片开关与长度）、`config_stale` 提示条与「按新配置重建」按钮及重建进度展示。
- `src/api/types.ts`/调用层按 M2 契约扩展：`SearchRequest` 新参数、`SearchResponse.applied`、`IndexConfig`/`ParentChildConfig`、`IkDictEntry` 等。
