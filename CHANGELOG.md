# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
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
