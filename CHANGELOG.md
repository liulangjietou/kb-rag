# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- M6 索引快照发布（docs/M6-CONTRACTS.md）：发布门禁通过/force 之后、状态切 RELEASED 之前，对关联
  知识库的物理索引执行不可变快照（ES `_clone`：源索引临时置 `index.blocks.write=true` → clone →
  两端解锁，段级硬链接毫秒级完成；Milvus 为同步批量读写拷贝）并固化当时的版本可见集
  （`visible_version_ids`）与快照索引清单（`index_snapshots`），任一库快照失败则发布中止、已建
  的本次快照回滚删除、版本停留在门禁结论状态可重试；经 RELEASED 版本（含 rollback 重新发布的
  历史版本）发起的对外调用固定检索这份快照与固化可见集，回滚即刻恢复历史知识状态，TESTING 灰度/
  chat-preview/管理台调试/评测仍走实时别名与当前激活集合；快照索引被误删时降级为实时别名并记
  `degraded=snapshot_index_missing`（M6 之前发布的旧 RELEASED 版本走同样路径但不记该标记，属历史
  数据形态而非故障）；`AppVersionPinChecker` 落地归档保护——文档版本被任意未清理应用版本（含
  SUPERSEDED）的固化可见集引用即 pin，`VersionRetentionService` 跳过之；新增按应用保留最近 3 个
  SUPERSEDED 版本快照的定时清理任务，超出的删物理索引并解除 pin，RELEASED 快照永不清理；
  `scripts/backup.sh`/`scripts/restore.sh`（mysqldump + ES 数据导出/mc mirror + MinIO 全量，
  产物带时间戳目录）与 `docs/backup-restore.md`（RPO/RTO 说明与演练步骤）；`scripts/seed-bench.py`
  零 Key 直写 10 万分片压测数据，P95 由 33.7ms 劣化至 39.0ms（+15.9%，≤20% 验收阈值内）。
  `docs/openapi/kb-server.yaml` 同步：`AppVersionResponse` 增 `index_snapshots`/
  `visible_version_kb_count`，`DocumentVersionResponse` 增 `pinned`/`pinned_by`，`degraded`
  枚举增 `snapshot_index_missing`（并补齐 M5 遗漏的 `route_fallback_all`），`info.version` 升至
  `0.7.0-m6`。
- M5 多知识库路由（docs/M5-CONTRACTS.md）：应用版本配置 `kb_id` 单库字段废弃为兼容可选项，
  新增 `kb_refs`（1..15 个知识库 + 配额权重，正整数，默认 1，`kb.retrieval.max-linked-kb`
  控制上限）；`RoutingService` 按需（路由开关开启且应用挂 ≥2 库时）调用 ChatProvider 做
  LLM 选库，输出与候选知识库白名单求交集，空交集/解析失败/超时/未配置对话模型一律降级为
  检索全部关联库并记 `degraded=route_fallback_all`（需求文档 §4.4 注入防护③），决策结果按
  query+候选集哈希缓存（`kb.retrieval.routing-cache-ttl-minutes`/`routing-cache-max-size`）；
  跨库检索基于库内排名做 Reciprocal Rank Fusion 合并（`CrossKbRrfFusion`），rerank 候选总
  预算（全局默认 50，非每库）按 `kb_refs` 权重比例分配到各库（`KbQuotaAllocator`，向下取整、
  余量归权重最高库，验收用例权重 3:1 分 50 得 38/12）；对外/管理 search、chat、chat-preview
  响应新增 `routed_kb_ids`（`applied` 信息条或顶层，SSE `done` 事件同增）与
  `RetrievalNode.metadata.kb_id`；门禁评测集绑定放宽为「所属知识库属于版本 kb_refs 并集」；
  旧版仅存单 `kb_id` 的快照读侧兼容翻译，无需迁移。`docs/openapi/kb-server.yaml` 同步全部
  M5 字段变更，`info.version` 升至 `0.6.0-m5`。
- 补齐 M4c OpenAPI 欠账（`docs/openapi/kb-server.yaml`，`info.version` 升至 `0.5.0-m4c`）：
  应用与版本全部端点（CRUD/`versions`/`gate-dataset`/`submit-test`/`release?force`/
  `rollback`）、控制台 `chat-preview`（JSON + SSE）、API Key 管理（`create`/`list`/`status`/
  `scope`/`rotate`/`delete`）、调用审计查询与统计（`/api-audit-logs`、`.../stats`）、对外
  `/api/v1/knowledge/search`、`/chat`（新增 `ApiKeyBearer` securityScheme 区分管理鉴权）、
  `AppVersionStatus` 八状态机、`GateVerdict`/`GateReason` 枚举、SSE 事件 schema 与
  `APP_ACCESS_DENIED`/`API_KEY_DISABLED`/`RATE_LIMITED` 等错误码，此前该增量因排期滞后于
  server 侧实现（M4c-CONTRACTS.md §6 已记录该欠账）。
- M4c 应用发布与开放能力：应用与版本八状态机（单应用唯一 RELEASED）、发布门禁（同语料双跑/容差 ε/有效 case 交集/四情形 LOG_ONLY/force 留痕/首发基线）、对外 knowledge search+chat（API Key 哈希鉴权、app_scope、令牌桶限流、SSE 流式、注入防护 prompt）、API Key 管理、审计落库与 180 天归档、Flyway V6。
- M4b 评测体系（docs/M4b-CONTRACTS.md）：评测集/case 的增删改查与分页、证据复核工作台
  （待复核 case 列表 + Top3 候选原文 + REANCHOR/DEPRECATE）、检索调试页一键收进评测集与
  检索结果反馈标注、Demo 示例评测集导入（按 file_name+content_hash_sha256 匹配库内文档、
  幂等）、评测运行配置矩阵（BM25_ONLY/VECTOR_ONLY/HYBRID/HYBRID_RERANK 一次提交产生 N 个
  run）与提交前费用预估、run 详情/命中明细下钻/同 dataset_revision 下的多 run 对比、
  三层嵌套指标（overall/span/document/single_turn/multi_turn 分组 × Recall/Precision/
  Hit Rate/MRR/NDCG + Wilson 95% 置信区间）；`docs/openapi/kb-server.yaml` 同步全部
  端点与 schema（枚举 AnchorType/CaseStatus/RunStatus/EvalMode 齐全），`ActivateImpact.
  affected_eval_case_count` 由 M4a 恒 0 占位改为真实统计说明；`.env.example` 新增
  `EVAL_JUDGE_MODEL`/`EVAL_OFFLINE_TIMEOUT_MS`/`EVAL_CONCURRENCY`/`EVAL_OVERLAP_THRESHOLD`/
  `EVAL_DEGRADED_RETRY`。
- M4a 文档版本与分片标注：同名文件重复上传按 major/minor 规则生成新版本（内容全同则不建版）、版本列表与激活/影响预检端点、即时回退与归档版本重建回退（rollback_mode）、非激活版本保留策略与归档清理、分片标注四操作（编辑/启禁用/合并/拆分，统一走事实源先行再双引擎同步）、父子分片禁用语义（disabled_child_ids 与 hide_parent_with_disabled_child 开关）、标注跨版本按 chunk_text_hash 精确继承与待复核清单、Flyway V4 建 t_kb_annotation。

- （M3）`demo/`：4 篇原创 RAG/知识库技术说明文档（覆盖 pdf/docx/xlsx/md 各一，中文，
  各篇 300-800 字）+ `demo/manifest.json`（文件名/标题/说明/建议 query 列表）+
  `demo/eval-cases.json`（10 条示例评测集，span 锚定证据摘录 + 关联文件名，结构对应
  需求文档 §6 t_kb_eval_case；本期只分发，导入功能排期 M4b）+ `demo/tools/generate_demo_docs.py`
  （docx/pdf/xlsx 可复现生成脚本，pdf 内嵌一张自绘流水线示意图用于验证 VLM 图片解析
  链路）+ `demo/README.md`
- （M3）`mappings/chat/memotrace.yml`：微信「留痕」/MemoTrace 聊天记录列名映射模板
  （对应 kb-rag-parser `POST /api/v1/parse/chat` 的 `mapping_profile` 默认档案，见
  M3-CONTRACTS.md §2.2）+ `mappings/README.md`（如何为新来源新增映射档案）
- （M3）`.env.example` 新增 `VISION_MODEL`/`VISION_TIMEOUT_MS`/
  `SCANNED_PAGE_TEXT_THRESHOLD`/`MAX_IMAGES_PER_DOC`/`DEMO_DATA_DIR`
- （M3）`docs/openapi/kb-parser.yaml` 同步 M3-CONTRACTS.md §2：`/api/v1/parse` 响应
  新增 `pages[].scanned`、`data.images[]`（`kind=embedded|page_render`）、
  `data.warnings[]`；新增 `POST /api/v1/parse/chat` 聊天记录（CSV/Excel）解析端点
- （M3）`docs/openapi/kb-server.yaml` 同步 M3-CONTRACTS.md §3：`model-status` 新增
  `vision_configured`/`vision_provider`/`vision_model`；新增解析预览与确认
  （`GET /api/v1/documents/{docId}/preview`、`POST .../confirm`、`POST .../reparse`、
  `POST /api/v1/kb/{kbId}/documents/confirm` 批量确认，`ProcessStatus` 增
  `PENDING_CONFIRM`）；新增聊天记录导入（`POST /api/v1/kb/{kbId}/chat-imports` 匹配预览
  + `.../confirm` 执行导入）；新增告警配置（`GET|PUT /api/v1/system/alert-config`、
  `POST .../test`）；新增 Demo 一键导入（`POST /api/v1/system/demo/import`、
  `GET /api/v1/system/demo/status`）；`IndexConfig` 新增 `clean_rules`/
  `parse_preview_required`/`chat_aggregation`
- （M3）`NOTICE` 增 DashScope qwen-vl-max（图片理解/OCR，M3-CONTRACTS.md §3.1）使用声明，
  并说明 PaddleOCR 在 M1-M3 均未引入（本地 OCR 兜底二期再评估，M3 扫描件 OCR 由 qwen-vl 承担）
- （M2）`es-ik/Dockerfile`：基于 `docker.elastic.co/elasticsearch/elasticsearch:8.11.4`
  安装 analysis-ik 插件（infinilabs 官方发布 zip，`IK_VERSION` 构建参数化，默认
  `8.11.4`）+ `docker-compose.es-ik.yml` override（build 该镜像替换 elasticsearch
  服务、挂载 `es-ik/config/IKAnalyzer.cfg.xml` 对接 kb-rag-server 词典热更新通道、
  Linux 下用 `extra_hosts: host.docker.internal:host-gateway` 补齐 macOS Docker
  Desktop 自带的 host 域名解析），README 增补「启用 ik」章节（M2-CONTRACTS.md §3）
- （M2）`scripts/benchmark.sh`：对指定知识库并发跑检索压测（`BASE_URL`/`TOKEN`/
  `KB_ID`/`QUERY_FILE`/`TOTAL`/`CONCURRENCY` 均可配置，默认内置 10 条中文查询、
  200 次、并发 5），纯 bash + curl + awk + sort 实现（不引入 jq/python 依赖），
  输出 P50/P95/P99 与错误数（含连接失败 `000` 的清晰提示），对应验收口径
  M2-CONTRACTS.md §7「基础链路 P95<2s」
- （M2）`docs/openapi/kb-server.yaml` 同步 M2-CONTRACTS.md §1.5/§3/§4 契约：search
  新入参（score_threshold/fusion/rerank_enabled/rewrite_enabled/messages/
  metadata_filter）与出参（`applied` 信息条、`RetrievalNode.metadata` 新增各路
  归一化分/rerank 分/child_ids）、`score_type`/`degraded` 枚举扩展、新增
  ik 词典 CRUD（`/api/v1/dict/ik`、`/api/v1/dict/ik/{dictId}`）与索引配置/重建
  端点（`PUT /api/v1/kb/{kbId}/index-config`、`POST /api/v1/kb/{kbId}/rebuild`）
- `docker-compose.lite.yml`：轻量模式中间件编排（MySQL 8.0 + Elasticsearch 8.11.4 单节点
  关闭安全模块 + MinIO），全部服务带 healthcheck / restart: unless-stopped / 固定镜像 tag /
  命名 volume
- `docker-compose.yml`：完整模式编排，在 lite 基础上（通过 Compose `include` 复用，避免
  重复维护）叠加 Milvus 2.4.x standalone（独立 etcd + 独立 milvus-minio，与应用侧 MinIO
  隔离）与 Redis 7.2.x（`--profile redis` 显式开启，标注 optional）
- `.env.example`：契约 §1 全部环境变量 + docker-compose 专用变量，中文注释标注零 Key 模式
  下可空的变量
- `scripts/preflight.sh`：部署前置检查（docker/内存/端口占用/占位口令检测）
- `scripts/backup.sh`：MySQL 全量 mysqldump + MinIO 数据卷全量导出，按份数轮转
- `docs/openapi/kb-server.yaml`、`docs/openapi/kb-parser.yaml`：M1 端点 OpenAPI 3.0 契约
  （含 RetrievalNode、统一错误响应、degraded 枚举）
- 开源工程基线文件：LICENSE (Apache-2.0)、NOTICE（MySQL/ES/Milvus/MinIO/Redis/MinerU
  许可声明）、SECURITY.md、CONTRIBUTING.md、Issue/PR 模板

### Notes

- 2026-07-26：M6-CONTRACTS.md §4 验收通过（零 Key 域）——V1 发布产生快照
  `kb_{id}_none_s1`（ES 实索引 + registry 行 + 两列固化），新文档版本激活后对外 search（V1）
  不含新内容、管理台调试含新内容（快照隔离实证）；V2 发布 s2 后 rollback V1，检索恢复历史状态且
  召回非空；旧文档版本 `pinned=true` 且 `pinned_by` 指向引用它的应用版本；误删快照后 RELEASED
  调用 `degraded=[snapshot_index_missing,…]` 且结果出自实时索引；M5 期旧格式 RELEASED 兼容调用
  不记该标记；备份-删库-恢复演练（`backup.sh` → `DROP DATABASE` + 删全部 `kb_*` 索引 →
  `restore.sh` 恢复 301 行 chunk/15 索引 → 检索命中非空）；seed 10 万分片压测
  P50=18.3/P95=39.0/P99=159.8ms，对比 100 分片基线 P50=19.9/P95=33.7/P99=128.8ms，
  **P95 劣化 15.9% ≤ 20% 验收阈值**，200/200 全 2xx。单测 606 项（新增 53）全过。Key 恢复后
  补验：向量路快照检索、Milvus 快照（需 full 模式）。本仓库范围内本次同步完成 M6 OpenAPI
  增量（`docs/openapi/kb-server.yaml` → `0.7.0-m6`）；`t_kb_app_version` 新增
  `visible_version_ids`/`index_snapshots` 两列的 Flyway V7 迁移脚本在 kb-rag-server 仓库，
  不在本仓库交付范围；compose/`.env.example`/需求文档 v1.12 回补由主会话另行处理
- 2026-07-26：M5-CONTRACTS.md §5 验收通过（零 Key 域）——双库路由关时两库都查且
  `node.metadata.kb_id` 覆盖两库；路由开 + 零 Key 时 `degraded` 含 `route_fallback_all`
  仍全库检索；权重 3:1 配额实测 `quotas={38, 12}`；M4c 旧版单库快照对外调用仍正常且
  `routed_kb_ids=[该库]`（读侧兼容）。单测 553 项（新增 53）全过；LLM 真实选库与 rerank
  参与的跨库排序待模型 Key 恢复后补验。本仓库范围内本次同步完成 M4c OpenAPI 欠账补齐 +
  M5 OpenAPI 增量，`t_kb_app_version.config` 的 JSON 结构变更（`kb_refs`/`routing`）无新增
  Flyway 迁移（沿用既有 JSON 列，读侧翻译兼容），迁移脚本本就在 kb-rag-server 仓库
  不在本仓库交付范围
- 本版本对应需求文档 v1.11 / M3-CONTRACTS.md 的 M3 里程碑增量（本仓库范围：Demo 文档集
  与生成脚本、示例评测集、聊天记录列名映射模板、`.env.example` 新增变量、OpenAPI 契约
  同步、NOTICE 声明）；`t_kb_image_asset` 表、`process_status` 增 `PENDING_CONFIRM` 等
  Flyway V3 迁移脚本在 kb-rag-server 仓库，不在本仓库交付范围。M3 只做 Demo **文档集**
  一键导入，示例评测集导入功能排期 M4b（需求文档同版本已同步修订 §10 M3/M4b 两行）
- 本版本对应需求文档 v1.8 / M2-CONTRACTS.md v1.0 的 M2 里程碑增量（本仓库范围：
  es-ik 镜像与 compose override、benchmark 压测脚本、OpenAPI 契约同步）；
  `t_kb_ik_dict`/`retrieval_config` 等 Flyway V2 迁移脚本在 kb-rag-server 仓库，
  不在本仓库交付范围
- 本版本对应需求文档 v1.8 / M1-CONTRACTS.md v1.0 的 M1 里程碑交付
- 不含 schema 变更（本仓库不承载数据库 migration，Flyway 脚本在 kb-rag-server 仓库）
