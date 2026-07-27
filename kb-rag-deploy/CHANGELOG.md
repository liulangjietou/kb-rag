# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Fixed
- 两处文档滞后于 M8 交付的修正：mappings/README.md「已知限制」仍写 TXT/HTML 降级二期，更新为
  M8 已交付（内置 liuhen_txt/liuhen_html 模板、自定义正则/选择器）并补充映射档案 CRUD 界面与
  t_kb_source_mapping 的指引；NOTICE 的 PaddleOCR 条目从"planned, not used"更新为 M8 已集成的
  可选依赖（requirements-ocr.txt、OCR_ENGINE 开关、模型权重不随仓分发），并同步修正 qwen-vl
  条目中"no local OCR engine involved"的过期交叉引用
- fusion 字段两处形状错位（kb-rag-web PR#14，用户实测报告）：①评测估算/提交把 fusion 发成
  {mode,rrf_k} 对象而 server 是字符串字面量，勾选混合检索/混合+重排即 Jackson 500；②应用配置页
  读写嵌套 retrieval.fusion 对象而 server 快照是扁平 fusion_mode/w_vec/rrf_k，未知字段被静默丢弃
  ——应用的融合设置端到端从未生效。两处对齐真实形状；管理端调试 search 两侧本就一致未动
- 评测报告与门禁双跑对比全列 NaN%（kb-rag-web PR#13，用户实测截图报告）：M4b 期 web 类型层把每个
  指标假设为 {value, ci_low, ci_high} 对象且该 ASSUMPTION 从未与后端定版核对，后端实际返回扁平
  数字 + 独立 recall_ci/hit_rate_ci —— 数字通过空值检查、.value 取出 undefined → NaN%；两个抽屉
  与 CSV 导出一并修正。伴生发现并修复 server 真实指标缺陷（kb-rag-server PR#17）：重叠切分让同一
  证据 span 命中多个候选时 IDCG 仍按声明证据数归一，NDCG 实测 2.948>1；理想相关数改取
  max(声明, 观测) 截断到 K
- 真流式从未生效（kb-rag-server PR#16，用户配有效 Key 首次真跑流式暴露）：chat-preview 与对外
  /knowledge/chat 把 SseEmitter 藏在 ResponseEntity<?> 后返回，Spring 按声明类型选返回值处理器、
  emitter 被交给消息转换器 → HttpMessageNotWritableException 500；修复为 produces=text/event-stream
  拆分独立流式方法，Accept 与 stream 字段错配报可操作 400（原"json Accept 也给流"承诺不可实现已移除，
  见 M4c-CONTRACTS.md §6 补记）。同 PR 新增 CHAT_GENERATE_TIMEOUT_MS（默认 60s）——生成与
  路由/改写共用 3s 预算导致真实生成必超时，现仅生成读上限独立放宽
- SSE 流式端点（chat/chat-preview）上抛出的业务异常被内容协商吃成裸 500：Accept 仅为
  text/event-stream 时 JSON 错误信封无法协商渲染，过期 token 的 401 语义被掩盖为
  Internal Server Error；修复为对 stream-only Accept 手写 JSON 信封绕过协商
  （kb-rag-server PR#15，用户实测发现）

### Added
- 开源发布就绪：README 通读全文并核对滞后——状态行由"M4b 里程碑"更新为"一期
  （M1-M7）已完成、二期进行中（M8 已完成、M9 开发中）"；补齐此前缺失的对应章节
  （多知识库路由 M5、应用发布与索引快照回滚 M6、GraphRAG 知识图谱 M7 可选启用、
  聊天记录 TXT/HTML 格式与映射档案维护 M8）；"总体文档"导航补
  `docs/ARCHITECTURE.md`/`docs/FLOWS.md` 链接；修正两处滞后表述（"对外 API Key
  开放平台网关是后续里程碑 M4c 范畴"已随 M4c 交付、"开源工程文档"小节里概括
  NOTICE 内容的 PaddleOCR 一词由"预留"改为如实反映 M8 起已是可选依赖，NOTICE.md
  正文本身留待独立的许可合规复核）；性能数字统一为已验收口径（M2 基础链路 P95<2s /
  完整链路含改写 P95<3s，M6 十万分片压测 P95 劣化 15.9%≤20%，出处 M2/M6-CONTRACTS.md）。
  新增 `.github/workflows/ci.yml`：push/PR 触发校验 `docker compose config -q`
  （lite/full 及各自叠加 es-ik override 四组合）、`docs/openapi/*.yaml` 只读
  `yaml.safe_load` 语法校验（不改动 openapi 内容）、`scripts/*.sh` 的 `bash -n`
  语法校验。新增 `UPGRADING.md`：compose 镜像 tag 固定原则、MySQL 走 Flyway
  自动迁移（禁手工 DDL、向后兼容一版、不可跨版本跳升）、ES/Milvus schema 变更走
  "从事实源重建 + 别名切换"、升级前先跑 `scripts/backup.sh`、CHANGELOG 条目如何
  标注 schema 变更，对应需求文档 §5"升级与迁移"条款
- M9 标注语义与图搜（docs/M9-CONTRACTS.md，二期收官批=清单项 5/6/7，至此二期 1-7 全部交付）：
  父片精确剔除（t_kb_chunk 落 parent_start/end_offset V10——切分副产物+截取一致性校验；禁用子片
  按偏移倒序剔除并以「（已省略被禁用内容）」替换、metadata 带 redacted_child_count；任一无偏移
  整片回退；子编辑/合并拆分/父编辑三路失效单点）；标注相似度辅助迁移（对称 Dice 字符 3-gram、
  同文档候选 top3 阈值 0.35、只推荐+migrate 幂等端点、不自动不批量）；图片 query（images 仅
  base64 ≤3张/5MB/10MB，VLM 转文本前缀拼接在改写之前，degraded=image_understanding_unavailable，
  纯图理解失败 INVALID_PARAM）；OpenAPI 升 0.10.0-m9、需求文档升 v1.15
- M8 导入与解析增强（docs/M8-CONTRACTS.md，二期第一批=二期清单项 1/2/3/4）：聊天记录 TXT/HTML
  两种新格式（TXT 内置留痕/微信 PC 双行模板、HTML 内置留痕选择器模板，均按公开约定编写待真实样例
  校准；行首正则命名捕获组与 DOM 选择器随 mapping profile 承载可自定义；不匹配行>30% 报可操作错误）；
  PaddleOCR 本地兜底（parser 可选依赖 requirements-ocr.txt，OCR_ENGINE=paddle 三级次序
  VLM→本地 OCR→跳过降级，未装依赖启动 fast-fail；实测校准为 paddlepaddle 3.3.1 + paddleocr 3.3.3
  的 3.x API）；聊天聚合重叠滑窗（window_overlap，默认 0 全兼容）与检索侧近重复归并（同会话
  msg_span 重叠率≥0.5 留最高分、merged_window_chunk_ids 留痕）；字段映射维护界面
  （t_kb_source_mapping 建表 V9+内置模板种子化+CRUD/复制端点+系统设置 tab）；OpenAPI 升 0.9.0-m8
- M8 期间修复两个遗留缺陷：①（M7）NEO4J_URI 为空时 Spring Boot Neo4j 自动配置默认连
  localhost:7687 并注册健康探测致整体 DOWN，破坏"空 URI 零影响"契约——排除该自动配置；
  ②（既有）UpdateIndexConfigRequest 的清洗与聊天聚合校验被父子分片 early-return 短路、
  单层库从未生效——校验上移为无条件执行
- M7 GraphRAG（docs/M7-CONTRACTS.md，一期收官）：知识库级实体/关系抽取（逐分片 LLM 抽取 JSON、
  注入防护分隔声明、输出强校验跳过计数落库 `t_kb_task.skipped_count`）入 Neo4j（`(:Entity)-[:REL]->
  (:Entity)`、`(:Entity)-[:MENTIONED_IN]->(:Chunk)` 溯源边，Neo4j 为可从 MySQL 重建的派生存储）；
  图检索路作为库内第三路进 RRF——query 轻量切词后经实体名 fulltext（cjk 分析器）匹配、N 跳扩展、
  溯源回 chunk，关联度=匹配分/(1+跳数)，回溯 chunk 复用 MySQL 事实源过滤谓词二次校验（版本可见集+
  未禁用），版本隔离不被图路击穿；开启图路的库库内融合强制 RRF（与 weighted 互斥校验单点）；
  Neo4j 未配置/不可达降级 `degraded=graph_route_unavailable`、快照上下文图路直接关闭；文档/知识库
  删除级联清理图数据（溯源边+孤立实体）；管理端五端点（config/extract/summary/entities/entity
  chunks）与知识图谱页（简版 SVG 力导向可视化）；Neo4j 5 以 compose profile=graph 可选启用
  （默认不启动，保 lite 4GB 承诺）；OpenAPI 升 0.8.0-m7、需求文档升 v1.13
- M7 验收中发现并修复 M6 遗留缺陷：分片禁用广播对已缺失的快照索引执行 ES bulk update 会触发
  `action.auto_create_index` 把该名字重建为空索引，使 `snapshot_index_missing` 降级安全网被静默
  击穿（RELEASED 调用查询空快照返回空结果且无降级标记）；修复为广播前 `indexExists` 探测、缺失
  跳过并 error 日志，附回归单测
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
