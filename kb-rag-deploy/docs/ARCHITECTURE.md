# kb-rag 架构文档

> 版本：v1.1（基线 = 一期 M1-M7 + 二期 M8/M9 及其后修复合并进各仓 main 的状态，2026-07-27；v1.0 基线为 M8）
> 日期：2026-07-27
> 作者：RichardFyoung / Claude
>
> **文档定位**：本文描述系统的实际实现架构（以代码为准），与以下文档互补——
> - `知识库需求文档.md`：需求与设计决策的唯一事实源（"为什么做、做什么"）
> - `M1~M9-CONTRACTS.md`：各里程碑的实现契约与已接受偏离（"每一期怎么做的"）
> - `openapi/kb-server.yaml`、`openapi/kb-parser.yaml`：HTTP 接口的唯一契约源
> - `FLOWS.md`：核心流程图（与本文配套阅读）

---

## 1. 系统总览

### 1.1 组件拓扑

```
                    ┌──────────────────────────────┐
                    │  kb-rag-web  (React 18 + AntD5)│  dev :20002
                    └──────────────┬───────────────┘
                                   │ /api、/actuator（Vite 代理 / Nginx 反代）
 ┌──────────────┐  REST(API Key)  ┌▼──────────────────────────────────────┐
 │  智能体应用    │◄───────────────►│  kb-rag-server (Java 17 / Boot 3)      │ :20000
 └──────────────┘  /api/v1/       │  kb-api → kb-app → kb-domain           │
                   knowledge/*    │                 ▲                      │
                                  │       kb-infrastructure ───────────────┤
                                  └──┬─────┬─────┬─────┬─────┬─────┬──────┘
                                     │     │     │     │     │     │ HTTP multipart
                                  MySQL   ES  Milvus MinIO Neo4j ┌──▼───────────────┐
                                 :13306 :9200 :19530 :9000 :7687 │ kb-rag-parser     │ :20001
                                 (事实源)(BM25/  (向量, (原件/ (图,  │ (Python/FastAPI)  │
                                        lite向量) full) 归档) 可选) │ PyMuPDF/openpyxl  │
                                                                 └──────────────────┘
```

- **kb-rag-server**：唯一的业务中枢。管理台 API、对外开放 API、索引管线编排、检索链路、**全部大模型调用**（嵌入/重排/对话/视觉四类 Provider）。
- **kb-rag-parser**：纯解析微服务。只做文件解析、版面/扫描页判定、图片抽取与可选本地 OCR，**不调用任何大模型**（M3 契约 §0 定版的职责边界）。
- **kb-rag-web**：管理台前端，无 SSR、无状态库（React Context），通过 Vite 代理 / 反代与 server 同源交互。
- **kb-rag-deploy**：入口仓。docker-compose（lite/full/es-ik/graph）、OpenAPI 契约、跨仓文档、备份恢复与压测脚本、Demo 素材。

### 1.2 端口契约（M1 定版）

| 服务 | 端口 |
|---|---|
| kb-rag-server | 20000 |
| kb-rag-parser | 20001 |
| kb-rag-web（dev） | 20002 |
| MySQL / ES / MinIO / Milvus / Redis / Neo4j | 13306 / 9200 / 9000+9001 / 19530+9091 / 6379 / 7687+7474 |

> MySQL 的 13306 是**宿主机映射端口**（避开本机默认 3306），容器内仍监听标准 3306；
> 其余中间件宿主机与容器端口一致。改端口只需调整 `.env` 的 `MYSQL_PORT`。

### 1.3 数据一致性原则

- **MySQL `t_kb_chunk` 是唯一事实源**；Milvus 与 ES 均为派生索引，可从 MySQL 幂等重建。
- 双写状态按"分片 × 物理索引"粒度记录在 `t_kb_chunk_index_sync`，由定时补偿任务重放（§3.7）。
- **无消息队列、无强制 Redis**：异步全部为 Spring `@Async` + 进程内线程池；跨存储一致性 = 同步状态表 + 定时补偿。限流计数与上传 Token 为进程内实现，明确记录为单实例部署的降级方案（多实例扩展是未来边界，不影响功能正确性）；管理台登录 Token 自 M9 后修复起落库 `t_kb_auth_token`（V11，仅存 SHA-256 哈希，单实例重启不再踢掉全部会话）。

---

## 2. 部署形态

| 形态 | compose 文件 | 服务集 | 内存档 |
|---|---|---|---|
| **轻量（lite，默认引导）** | `docker-compose.lite.yml` | MySQL 8.0.36 + ES 8.11.4 + MinIO（+ Neo4j `profile=graph` 可选） | 8GB |
| **完整（full）** | `docker-compose.yml`（`include` lite，复用其服务定义） | lite 全部 + Milvus v2.4.15（etcd + 独立 minio + standalone）+ Redis 7.2.5（`profile=redis,optional`） | 24GB |
| **ik 分词** | `docker-compose.es-ik.yml`（override 层） | 仅覆盖 elasticsearch 为自构建 `kb-rag-es-ik:8.11.4` 镜像 | — |

关键设计：

- **D16 双引擎切换**：`kb.vector.engine=es|milvus`。lite 模式 ES 单引擎同时承担 BM25 与向量（dense_vector kNN）；full 模式 Milvus 承担向量、ES 只做 BM25。两模式共用 `VectorStore` 抽象与同一检索链路，分数量纲由抽象层统一（§3.4）。
- **零 Key 启动**：不配任何模型 Key 也能完整跑通"上传→BM25 检索"。实现机制见 §3.3 的 `Unconfigured*Provider` 模式；检索自动降级 BM25 单路并透出 `degraded=[vector_route_unavailable]`。
- **Neo4j 可选**：`profile=graph` 默认不启动；`NEO4J_URI` 留空即注入 `DisabledGraphStore`，图能力整体禁用、其余功能零影响（含显式排除 `Neo4jAutoConfiguration`，防止 driver 在 classpath 上导致健康探针误报 DOWN）。
- **ik 词典热更新**：ES ik 插件远程词典指向 server 的免登录端点 `/internal/dict/ik/{ext|stop}.txt`（带 Last-Modified/ETag，约 60s 轮询）；词条以 DB（`t_kb_ik_dict`）为源，容器重建不丢。注意：切换 ik 镜像后存量索引 analyzer 仍是 standard，需触发重建才生效。
- **milvus-minio 与应用侧 MinIO 严禁复用**同一实例/bucket（compose 内已隔离为两个服务）。

---

## 3. kb-rag-server 架构

### 3.1 Maven 模块与依赖方向（六边形架构）

基础包 `io.kbrag`，Spring Boot 3.3.9，五模块严格单向依赖：

```
kb-api ──► kb-app ──► kb-domain ──► kb-common
   └─────► kb-infrastructure ──► kb-domain ──► kb-common
```

| 模块 | 职责 | 关键内容 |
|---|---|---|
| **kb-common** | 无 Spring 依赖的基础件 | `Result` 统一响应信封、`ErrorCode`、`BizException`/`ProviderException`、`JsonUtil`/`HashUtil`、`RequestIdHolder`、`KbConstants`（业务 ID 前缀与 `kb-sk-` Key 前缀） |
| **kb-domain** | 实体 + 端口 + 纯领域算法 | MyBatis-Plus 实体/Mapper（与 23 张业务表一一对应）、30+ 枚举、60+ 领域模型、**13 个出站端口接口**（`domain.port`）、无状态领域服务（切分/融合/指纹/评测指标/门禁裁决/标注迁移相似度等纯函数）、`KbProperties`（`@ConfigurationProperties(prefix="kb")`，二十余个嵌套段） |
| **kb-infrastructure** | 端口实现，按外部依赖分包 | `search.es` / `search.milvus` / `graph` / `provider.{chat,embedding,rerank,vision}` / `storage` / `parser` / `notify` / `config` |
| **kb-app** | 应用编排层（架构主体） | 15 个业务域包：`kb` / `document` / `index` / `retrieval` / `graph` / `annotation` / `eval` / `appcenter` / `openapi` / `chat` / `alert` / `dict` / `auth` / `system` / `config` |
| **kb-api** | HTTP 边界与装配点 | 23 个 Controller、过滤器/拦截器、SSE、健康探针、`application.yml`、Flyway 脚本；`@SpringBootApplication(scanBasePackages="io.kbrag")` 为唯一装配点 |

分层规则：Controller 只依赖 kb-app 的 Service 与 kb-domain 的 model/enum；kb-app 只依赖端口接口，**从不依赖 kb-infrastructure 具体类**；kb-infrastructure 实现端口，与 kb-app 互不感知。

### 3.2 领域端口与实现（13 个）

| 端口 | 实现（默认 / 降级） | 说明 |
|---|---|---|
| `EmbeddingProvider` | `DashScopeEmbeddingProvider` / `UnconfiguredEmbeddingProvider` | `embed`、`dimension`（维度由 Provider 声明，固化进索引名嵌入段）、`maxBatchSize` |
| `RerankProvider` | `DashScopeRerankProvider` / `UnconfiguredRerankProvider` | 超时 1.5s 降级粗排 |
| `ChatProvider` | `DashScopeChatProvider` / `UnconfiguredChatProvider` | 改写/路由/生成/judge 共用 |
| `ChatProviderFactory` | `ModelChatProviderFactory` | 按模型名缓存实例——应用版本快照的 `chat_model` 经此生效 |
| `VisionProvider` | `DashScopeVisionProvider` / `UnconfiguredVisionProvider` | 图片文本代理（VLM 调用全部在 server 侧） |
| `VectorStore` | `EsVectorStore`（lite）/ `MilvusVectorStore`（full） | `ensureIndex/upsert/delete/updateEnabled/snapshotIndex/search`；分数统一见 §3.4 |
| `FulltextStore` | `EsFulltextStore` | BM25 检索 |
| `GraphStore` | `Neo4jGraphStore` / `DisabledGraphStore` | 实体/关系/溯源边；Neo4j 为派生存储可重建，无对应 MySQL 表 |
| `ObjectStorage` | `MinioObjectStorage` | 原件/解析产物/图片/审计归档；预签名 URL |
| `DocumentParserClient` | `HttpDocumentParserClient` | RestClient 调 parser 三端点（§4.4） |
| `WebhookNotifier` | `HttpWebhookNotifier` | 告警外发 |
| `TokenEstimator` | `SimpleTokenEstimator` | 分片长度以 token 计、`max_content_length` 预算 |
| `VersionPinChecker` | `AppVersionPinChecker` | 被应用版本可见集引用的文档版本禁止归档（已下线版本同样 pin——chunk 正文只在 MySQL） |

**零 Key / 能力开关统一装置**：`ModelProviderConfig` 是唯一读模型凭据的地方，凭据为空即注入 `Unconfigured*` 实现；`GraphStoreConfig`（NEO4J_URI 空 → `DisabledGraphStore`）与 `MilvusClientConfig`（MILVUS_URI 空 → 不建 client、不注册健康探针）镜像同一模式。上游代码只写 `isConfigured()/isEnabled()` 一个分支，全链路无 null 检查——这是需求 §5"防御式编程只做一处且高复用"的落地点。

### 3.3 索引命名与别名管理

- `IndexNaming`（domain 纯函数）：物理索引三段命名 `kb_{kbId}_{嵌入段}_{快照段}`；零 Key 嵌入段为 `none`；完整模式 ES 索引嵌入段为 `bm25`（换嵌入模型不抖动全文索引）；快照段 `s{seq}` 为库级序列。
- `IndexAliasManager`（kb-app）：物理索引创建、别名绑定、`t_kb_index_registry` 注册的唯一持有者。**业务读写只寻址别名**；发布快照索引例外——不挂别名、按物理名直查（M6 定版）。
- 嵌入模型切换与发布快照是同一套原语："建新物理索引 → 回填 → 别名原子切换 / 注册"。ES 侧别名切换用 `updateAliases` 原子操作 + `is_write_index`（M3 验收修复：此前 putAlias 追加会导致别名双指向、写入永久失败）。
- **重建方式定版**（v1.9）：切分配置变更走**同物理索引内按文档原子替换 chunk**（先写新片再删旧片）；"新索引+别名切换"仅用于嵌入切换、lite→full 迁移、引擎 schema 变更三类场景。

### 3.4 检索链路（`io.kbrag.app.retrieval`）

`RetrievalService` 固定阶段顺序（每级可选但永不重排）：

```
图片 query 预处理(M9, 可选) → 改写 → 多库路由 → 双路/三路召回(子片粒度) → 库内融合(RRF/加权)
     → 跨库 RRF → 近重复窗口归并 → rerank → 父子归并(含禁用子片精确剔除) → 阈值过滤 → top_n
```

关键协作者与设计不变式：

| 类 | 不变式 |
|---|---|
| `ImageQueryService`（M9） | 对外/预览端点可带 `images[]`（仅 base64，≤3 张、单张 5MB、总量 10MB，越界 INVALID_PARAM）；逐张 VisionProvider 转文本、以 `[图片内容] ` 前缀拼到 query 尾部（发生在改写之前，图片语义参与改写）；任一失败即忽略全部图片降级纯文本并记 `image_understanding_unavailable`；纯图片且理解失败返回 INVALID_PARAM |
| `RewriteService` | 改写超时 800ms 降级原 query；改写结果只作检索词、不回填任何 prompt（注入防线④）；Caffeine 缓存 |
| `RoutingService` | LLM 提议、**白名单裁决**（结果与候选库集合取交集，注入防线③）；失败/未命中回退全部关联库（`route_fallback_all`）；缓存 key=query+候选集+生效 prompt，失败不入缓存 |
| `RetrievalIndexContextResolver` | **调用上下文三分支的唯一分辨点**：RELEASED → 冻结快照索引+固化可见集；TESTING/调试/评测 → 实时别名+当前激活集合；旧 RELEASED（无快照）→ 兼容实时、不记降级 |
| `VectorScoreNormalizer` | 跨引擎向量分统一：ES `(1+cos)/2` 先还原、Milvus 原生 cosine，统一映射到 [0,1]；两实现共享一份"引擎一致性单测" |
| `FusionRouter` → `RrfFusion`/`WeightedFusion` | 加权融合先按候选集 min-max 归一化；开图路的库强制 RRF（`GraphFusionPolicy`——图关联度是第三种量纲） |
| `CrossKbRrfFusion` + `KbQuotaAllocator` | 跨库只用名次不用分数；rerank 候选预算是**全局总量**按权重配额切分（向下取整、余量归最高权重库） |
| `RerankService` | 候选上限 50（父子开启时联动 `max(50, top_n×5)`，硬上限 100）；超时 1.5s 降级粗排；rerank 分是唯一跨库跨路可比的绝对分 |
| `ParentChildMerger` | 召回/融合/rerank 全在子片粒度，之后按 parent_id 归并、父片得分取子片 max；目标父片数 `max(top_n×3, 20)` |
| `DisabledChildVisibility` + `ParentTextRedactor`（M9） | 含禁用子片的父片：默认整片返回并标注 `disabled_child_ids`；M9 起对偏移非 null 的禁用子片按 [start,end) **倒序**精确剔除文本段、置省略标记与 `redacted_child_count`；任一禁用子片偏移为 null 则整片回退（半剔除比不剔除更误导）；库级开关 `hide_parent_with_disabled_child` 打开则整父片隐藏 |
| `ScoreThresholdPolicy` | 阈值只作用于 rerank 分；降级时作用于标准 cosine 分；BM25 单路阈值失效并透出 `threshold_inactive`；`score_type` 必须如实上报 |
| `ActiveVersionResolver` | 版本可见集缓存（TTL 可配）+ 失效通知；快照路径绝不经过此处 |
| `EngineChunkCleaner` | 召回命中事实源已删分片时反向清理引擎（**快照路径关闭自愈**，防跨索引误删——M6 红线） |
| `OfflineExecutionContext` | ThreadLocal 离线评测标记：超时放宽至 10s、降级不计入生产监控 |

**强制过滤不变式**：各路召回必须在引擎侧过滤 `document_version_id ∈ 版本可见集` 且 `enabled=1`，链路强制、不可由参数关闭；图路回溯 chunk 后在 MySQL 事实源二次复核同一谓词（"图提议、MySQL 裁决"）。

**图路（M7）**：`GraphRetrievalService` 检索侧零 LLM——query 轻量切词（`GraphQueryTokenizer`）→ Neo4j 实体名 fulltext（cjk 分析器）匹配 → N 跳扩展（默认 2）→ 溯源边回 chunk，关联度 = 归一化匹配分 / (1+跳数)（`GraphRelevanceScorer`）；快照上下文图路直接关闭且不记降级（能力边界而非故障）。抽取侧 `GraphExtractionService` 逐分片 LLM 抽取 + 输出强校验（非法跳过计 `t_kb_task.skipped_count`）。

**degraded 枚举**（`DegradedReason`，与需求 §4.8 一一对应）：`query_rewrite_timeout/error/unavailable`、`rerank_timeout/error/unavailable`、`vector_route_unavailable`、`route_fallback_all`、`threshold_inactive`、`snapshot_index_missing`、`graph_route_unavailable`、`image_understanding_unavailable`（M9）。

### 3.5 索引管线（`io.kbrag.app.document` + `io.kbrag.app.index`）

- **上传事务只持久化事实**（MinIO 原件 + document/version 行），管线经 `TransactionSynchronization.afterCommit` 提交异步执行——异步 worker 用独立连接读版本行，事务内触发会产生提交竞态（M1 联调发现的真实缺陷，全仓仅 `DocumentService` 与 `EngineChunkCleaner` 两处使用该手法）。
- `IndexPipelineService` 是管线主编排，五个 `@Async(INDEX_EXECUTOR)` 入口：`submit`（新上传）/ `submit(versionId, reuse)`（指纹复用）/ `submitRestore`（归档版重建）/ `submitRebuild`（配置重建）/ `submitConfirm`（预览确认后续跑）。
- 阶段：解析（parser HTTP）→ 清洗/脱敏（`DocumentCleaner`/`TextDesensitizer`）→ 图片资产与 VLM 文本代理插回（`ImageAssetService`/`ImagePlaceholderResolver`，单图失败不失败整篇）→ 切分（`SplitterRouter` 策略路由，未知 code 回落定长；父子两级切分 `ParentChildSplitter`）→ 嵌入（`ChunkEmbedder`，零 Key 全部 SKIPPED）→ 双写（`ChunkIndexWriter`：**先写 PENDING 同步行、再调引擎、再更新状态**——补偿扫描的唯一证据链）。
- **指纹复用**：`VersionFingerprintFactory` 产出 content_hash/解析指纹/分片指纹/嵌入版本，`VersionArtifactReuser` 在全匹配时复制上一构建的 chunk 行（新 ID、重写父链），跳过解析与切分。
- **版本机制**：`DocumentVersionPlanner` 一次回答重复判定/版本号（major.minor）/可否复用；`DocumentVersionActivator` 激活时旧 active 退回 READY（支持秒级回滚）；`VersionRetentionService` 异步清理超保留窗口（默认 3）的旧版 chunk（保留原件与解析产物）；`AppVersionPinChecker` 保护被快照引用的版本不被清理。
- **标注跨版本（M4a + M9）**：`AnnotationInheritanceService` 按 chunk_text_hash 精确继承禁用类标注（新版本不自动继承其他标注）；M9 起 `AnnotationMigrationAdvisor`（domain 纯函数）以归一化字符 3-gram **Dice 系数**（对称指标——刻意不用评测的非对称重叠率）为待复核标注懒计算迁移建议（阈值 `kb.annotation.migration-min-score=0.35`、top 3、候选限同文档当前激活版本、短文本不推荐），`POST /api/v1/annotations/{id}/migrate` 人工逐条确认（仅禁用/编辑两类，幂等；刻意不做自动与批量迁移——误迁移代价大于逐条确认成本）。

### 3.6 应用发布与开放 API（`appcenter` + `openapi`）

- `AppVersionService`：八状态机全部迁移收敛于 `transition` 一个方法，合法迁移定义在 `AppVersionStatus` 枚举上；"至多一个 RELEASED"由表上 `released_slot` 生成列 + 唯一索引双保险。
- `ReleaseGateService`（`GATE_EXECUTOR`，与评测池分离防自等待死锁）：同语料同时刻双跑（候选配置 vs 当前正式版配置，复用 `EvalRunService`）；`ReleaseGateJudge`（纯函数，唯一裁决点，含 1e-9 浮点余量）三态裁决：通过 / 拦截 / 仅记录不拦截；`GateMetricsRecomputer` 在双方共同判定的 case 交集上重算指标，堵分母漂移。
- `AppReleaseSnapshotService`：门禁裁决后、RELEASED 生效前，**同时冻结** `index_snapshots`（`IndexSnapshotService`：ES `_clone` / Milvus queryIterator 拷贝，不挂别名）与 `visible_version_ids`——只冻结索引不冻结可见集正是"回滚后召回全空"缺陷的根源（v1.6 修复）。
- 对外 API `/api/v1/knowledge/{search,chat}`：**独立的 `ApiKeyAuthFilter` servlet 过滤器链**（刻意不与管理台 Bearer 拦截器共用入口）；`ApiKeyFactory` 一把 Key 三形态（明文仅创建时返回一次 / SHA-256 digest 用于鉴权 / 前缀用于展示）；`ApiRateLimiter` 进程内令牌桶；`RequestOverridePolicy` 白名单只放 4 个响应形态参数（top_n/score_threshold/metadata_filter/max_content_length），越界拒绝而非忽略；`ApiAuditService` 异步落审计（拒绝也记录、401 不落 429 落）、`ApiAuditArchiveService` 定时归档 MinIO 后分批物理删除；`QueryDigestFactory` 对审计 query 无条件脱敏截断。
- chat SSE 事件契约：`message_delta`* → `references`（元素为与 search nodes 同构的 RetrievalNode）→ `done`（含 request_id/用量/degraded）或 `error`；生成模型取应用版本快照配置，经 `ChatProviderFactory` 生效；`ChatPromptAssembler` 固定分隔符包裹检索内容（注入防线①）。

### 3.7 异步与定时（无 MQ 架构的支撑件）

线程池（`AsyncConfig`，统一 `TaskDecorator` 透传 requestId 到 worker 线程）：

| 池 | core/max/queue | 用途与设计理由 |
|---|---|---|
| `indexTaskExecutor` | 2/4/200 | 索引管线；刻意小 + 有界队列，防饿死控制台 |
| `retrievalTaskExecutor` | 4/16/**0** | 检索超时保护；队列为 0，宁可快速降级不排队 |
| `evalTaskExecutor` | 2/6/50 | 评测 run（case 级并发另由 `kb.eval.concurrency` 控制） |
| `gateTaskExecutor` | 1/4/20 | 门禁双跑监督；**必须与评测池分离**，否则 gate 排在自己等待的 run 前面死锁 |
| `auditTaskExecutor` | 1/4/2000 | 审计异步写；异常只记日志绝不上抛 |
| `chatStreamTaskExecutor` | — | SSE 流式生成 |

定时任务（`@Scheduled`，`SchedulingConfig` 单独持有 `@EnableScheduling` 便于单测排除）：

| 任务 | 触发 | 职责 |
|---|---|---|
| `IndexSyncCompensationService` | fixedDelay 30s | 扫 FAILED + 超时 PENDING 同步行，按物理索引分组幂等重放 |
| `AlertEvaluator` | fixedDelay 60s | 任务连续失败 / 降级率 / 双写积压三类触发 + 静默期 |
| `ApiAuditArchiveService` | cron 03:30 | 审计日志归档 MinIO → 分批物理删除 |
| `AppSnapshotRetentionService` | cron 04:15 | SUPERSEDED 版本快照按保留数清理（RELEASED 永不清理） |

### 3.8 数据模型（23 张业务表，Flyway V1-V11）

全部 InnoDB，统一 `id / created_at / updated_at / lock_version / deleted`，审计字段由 `AuditFieldFiller` 填充，业务 ID 带类型前缀（kb/doc/dv/ck/task/img/upt/an/evds/evc/evr/evre/app/av/ak/aud/smp）。

| 迁移 | 表 / 变更 |
|---|---|
| V1（M1 基线） | `t_kb_knowledge_base`、`t_kb_document`、`t_kb_document_version`、`t_kb_chunk`、`t_kb_chunk_index_sync`、`t_kb_index_registry`、`t_kb_task`、`t_kb_admin_user`、`t_kb_system_config`、`t_kb_login_audit` |
| V2（M2） | `t_kb_ik_dict`；知识库加 `retrieval_config` |
| V3（M3） | `t_kb_image_asset`；文档加 `source_key`（聊天逻辑文档标识） |
| V4（M4a） | `t_kb_annotation`（幂等键 + inherit_status） |
| V5（M4b） | `t_kb_eval_dataset` / `t_kb_eval_case` / `t_kb_eval_run` / `t_kb_eval_result` |
| V6（M4c） | `t_kb_app` / `t_kb_app_version`（`released_slot` 生成列唯一约束）/ `t_kb_api_key` / `t_kb_api_audit_log` |
| V7（M6） | 应用版本加 `visible_version_ids` + `index_snapshots` |
| V8（M7） | `t_kb_task` 加 `skipped_count`（图抽取跳过计数） |
| V9（M8） | `t_kb_source_mapping`（映射档案，启动播种内置模板、只补缺不覆盖） |
| V10（M9） | `t_kb_chunk` 加 `parent_start_offset` / `parent_end_offset`（子片在父片中的 [起,止) 字符偏移，切分副产物、不做事后反查；子片编辑/合并/拆分及父片编辑时失效置 null） |
| V11（M9 后修复） | `t_kb_auth_token`（管理台登录 Token 落库，仅存 SHA-256 哈希，24h TTL 语义不变） |

引擎侧可过滤字段全集（Milvus 标量 / ES filter，建索引时显式声明）：`kb_id`、`doc_id`、`document_version_id`、`enabled`、`parent_id`、`chunk_type`、`tag_ids`、`session_id`、`sender`、`msg_time`、`chunk_seq`；其余 metadata 只存 MySQL。新增可过滤维度视为 schema 变更，走索引重建迁移。

---

## 4. kb-rag-parser 架构

### 4.1 技术栈与定位

Python 3.11+ / FastAPI + Uvicorn / pydantic 2。解析实现：**PyMuPDF**（pdf）、python-docx、openpyxl、标准库 csv/html.parser；可选 `requirements-ocr.txt`（paddlepaddle 3.3.1 + paddleocr 3.3.3，默认不装）。

> 注意：需求文档 §8 技术栈中"MinerU（pdf）"为原始选型，**实际实现未集成 MinerU**（全仓无引用；NOTICE 中为预留声明"does NOT use MinerU in M1"）。当前 pdf 路线 = PyMuPDF 文本层抽取 + 扫描页 150dpi 渲染，OCR 由 server 侧 VLM 或本地 PaddleOCR 承担。

### 4.2 API（统一信封 `{code, data, message, request_id}`，`code ∈ {OK, PARSE_FAILED}`）

| 端点 | 用途 |
|---|---|
| `GET /health` | 存活探针 |
| `POST /api/v1/parse` | multipart `file` + `file_ext`（pdf/docx/txt/md/xlsx/csv）→ markdown + 按页文本（`scanned`/`ocr_source` 标记）+ 图片 base64（`kind=embedded|page_render`）+ `warnings[]` |
| `POST /api/v1/parse/chat` | multipart `file` + `file_ext`（csv/xlsx/txt/html）+ 可选 `mapping_profile`/`profile_yaml` → 统一 ChatMessage 会话结构 + `skipped` 统计 |

### 4.3 模块结构

- `main.py`：端点 + `ThreadPoolExecutor(4)` 跑阻塞解析 + `asyncio.wait_for` 300s 超时；所有可恢复失败归一化为信封错误。
- `config.py`：全部常量单点（100MB 文件上限、zip 解压 500MB/2000 条目上限、扫描页文本阈值、图片 100 张/10MB 上限、TXT 不匹配行 30% 失败线等）。
- `parsers/`：`BaseParser` 策略接口 + `registry.py` 查表分派；pdf（扫描页判定 + 渲染，扫描页不再另抽内嵌图防双计）/ docx（段落表格图片按原始顺序，占位符插回）/ excel+csv（markdown 表格，每 sheet 一个 page_no）/ text。
- `chat/`：`parser.py` 按格式编排；`mapping.py`（MappingProfile：csv/xlsx 候选列名 + txt 行正则 + html 选择器三段配置，来源优先级 请求内联 profile_yaml > 本地 mappings/*.yml > 按扩展名内置默认）；`txt_adapter.py`（行首正则 + 续行归并）；`html_adapter.py`（仅标准库的最小 DOM 选择器引擎，剥 script/style，img 只判存在不下载）；`normalize.py`（时间戳/消息类型归一）。语音/视频消息剔除并计入 `skipped`。
- `ocr/engine.py`：`OcrEngine` 策略（NoOp / Paddle 懒加载），`OCR_ENGINE=paddle` 未装依赖时启动 fast-fail；单页 30s 超时、按页降级绝不整篇失败。三级次序：**server 侧 VLM（有 Key）→ 本地 PaddleOCR（离线）→ 跳过**；本地 OCR 成功回填 `pages[].text` 并置 `ocr_source=paddle`，server 据此跳过 VLM。
- `security.py`：zip-slip/zip 炸弹校验、defusedxml 全局加固（XXE）；解析阶段无任何出站网络请求（SSRF 基线）。
- `encoding.py`：utf-8-sig → utf-8 → gbk 探测，`errors=replace` 兜底永不抛。

### 4.4 与 server 的交互

server 侧 `HttpDocumentParserClient`（`kb-infrastructure`）经 `PARSER_BASE_URL` 调用；`X-Request-Id` 头透传实现两侧日志串联；M3 起新增字段全部防御式读取，双向兼容任意部署顺序；调用方仅 `IndexPipelineService` 与 `ChatImportService`（经 `DocumentParserClient` 端口）。

---

## 5. kb-rag-web 架构

### 5.1 技术栈

Vite 8 + React 18 + TypeScript 6 + Ant Design 5 + react-router-dom 6 + axios；lint 用 oxlint。无状态库（React Context：`AuthContext` / `ModelStatusContext`）。dev 端口 20002，`/api` 与 `/actuator` 代理到 20000。

### 5.2 路由与页面

守卫嵌套 `RequireAuth → RequirePasswordChanged → AuthenticatedShell(ModelStatusProvider + MainLayout)`：

| 路由 | 页面 |
|---|---|
| `/login`、`/change-password` | 登录（防爆破提示）、首登强制改密 |
| `/kb`、`/kb/:kbId` | 知识库列表；详情（文档上传/状态轮询/分片标注 ChunkDrawer/版本 VersionDrawer/索引配置/聊天导入向导/图谱 GraphTab） |
| `/search` | 检索调试（参数面板、分数明细、degraded 告警、收进评测集） |
| `/chat` | 问答调试（JWT 走 `/apps/{id}/chat-preview` SSE） |
| `/apps`、`/apps/:appId` | 应用中心（配置 / 版本与门禁 / API 调试三 tab） |
| `/eval` | 评测中心（数据集 / case 标注 / 证据复核 / 运行报告四 tab） |
| `/settings` | 系统设置（模型状态 / ik 词典 / API Key / 审计 / 告警 / 导入映射） |

### 5.3 与后端的三条通道

1. `api/request.ts`：axios 主通道，`baseURL=/api/v1`，Bearer 注入、401 统一跳登录、`unwrap` 拆信封。
2. `api/chatStream.ts`：SSE 驱动用原生 fetch（刻意绕过 401 拦截器），同时服务问答调试（JWT）与 API 调试 tab（API Key）。
3. `api/publicApi.ts`：API Key 直连对外端点，429 读 Retry-After。

### 5.4 关键组件约定

- `utils/kbRefs.ts`：`kb_refs` 读侧归一化**唯一防御点**（兼容旧快照单 `kb_id`）；所有版本路由 KB 的调用点必须走这里。
- `utils/statusMeta.ts`：20+ 枚举 → Tag 颜色与中文标签的唯一真源。
- `GraphVisualization.tsx`：自实现 Fruchterman-Reingold SVG 力导向（零依赖、seeded random 防抖动），刻意不引 @antv/g6。
- XSS 约束：解析预览等一律 preformatted 纯文本渲染，全仓不使用 `dangerouslySetInnerHTML`。

---

## 6. kb-rag-deploy 结构

| 内容 | 说明 |
|---|---|
| compose 三件套 | 见 §2；full 经 `include` 复用 lite，避免双处维护漂移 |
| `es-ik/` | ik 插件镜像 Dockerfile（参数化 ES/IK 版本）+ `IKAnalyzer.cfg.xml`（远程词典指向 server 热更新端点） |
| `scripts/preflight.sh` | 部署前置检查（内存档位/端口/占位口令），部署链路唯一防御式校验入口 |
| `scripts/backup.sh` / `restore.sh` | mysqldump 全量 + ES `_snapshot`（fs 仓库 `backup/es-repo`）+ mc mirror；恢复固定顺序 MySQL → ES → MinIO，fast-fail；RPO ≤24h，已做过真实"删库-恢复"演练（见 `backup-restore.md`） |
| `scripts/benchmark.sh` | 纯 bash+curl 压测（P50/P95/P99），验收口径 P95<2s；`seed-bench.py` 零 Key 直灌 10 万分片种子数据 |
| `demo/` | 4 篇原创文档（md/docx/pdf/xlsx 各一，字节级可复现生成）+ `eval-cases.json`（10 条，含文档级锚定图片 case，按文件名+content_hash 关联导入） |
| `mappings/` | 聊天记录列名映射模板分发（memotrace 等） |
| `docs/` | 需求文档、M1-M9 契约、OpenAPI（`kb-server.yaml` 0.10.0-m9，75 条路径 / `kb-parser.yaml` 0.9.0-m8，3 端点）、备份恢复手册、本文档与 `FLOWS.md` |

---

## 7. 横切关注点

### 7.1 配置五层模型

①环境变量/yml（`KbProperties`，全部带本地默认值）→ ②全局系统设置（`t_kb_system_config`，Provider/Webhook 等）→ ③知识库级（`index_config`/`retrieval_config` JSON）→ ④应用版本快照（发布固化，不再回落全局）→ ⑤API 请求级覆盖（白名单 4 参数）。检索侧三层合并在 `RetrievalSettings`（请求 > 库默认 > 部署默认）。

### 7.2 安全

- **两条独立鉴权链**：管理台 Bearer Token（`AuthInterceptor`，Token 哈希落库 `t_kb_auth_token`、防爆破锁定、首登随机密码强制改密）与对外 API Key（`ApiKeyAuthFilter` 独立过滤器链、哈希存储、app_scope 授权范围、令牌桶限流）。
- **Prompt 注入四防线**：①生成/judge prompt 固定分隔符包裹资料原文；②LLM 切分输出强校验（非法降级按长度切）；③路由白名单交集裁决；④改写结果仅作检索词。
- 解析侧基线：magic number 校验、zip-slip/炸弹、XXE（defusedxml）、SSRF（解析期零出站）；前端零 `dangerouslySetInnerHTML`；聊天导入默认脱敏（手机号/身份证/银行卡 16-19 位）；审计 query 无条件脱敏；MinIO 私有桶 + 限时预签名 URL。

### 7.3 可观测

- `request_id` 全链路：`RequestIdFilter` 入口生成 → MDC → 响应头 → `X-Request-Id` 透传 parser 与 Provider 调用日志 → `TaskDecorator` 带入异步线程。
- 健康：Actuator 组合探针（ES 必选；Milvus/Neo4j 仅配置时注册；MinIO；parser `/health`）。
- 告警：`AlertEvaluator` 三类触发 + 静默期，Webhook 未配置降级界面红点；日志仅 info/error、英文、错误码占位符。

### 7.4 一致性手法汇总

| 问题 | 手法 |
|---|---|
| 事务内触发异步读不到未提交行 | `afterCommit` 提交管线（仅 2 处，模式统一） |
| 双写部分失败 | 先写 PENDING 同步行再调引擎；30s 补偿扫描按物理索引重放 |
| 删除留空洞 vs 重复 | 先写后删（新引擎文档事务内写、旧文档删除挂 afterCommit）：回滚宁留可见重复、不留补偿看不见的空洞 |
| 回填与在线写竞态 | 双目标写入期 + updated_at 水位二次校验补写 |
| 快照与实时索引互踩 | 快照索引不挂别名、自愈/补偿/重建只作用实时别名；禁用广播前 indexExists 探测（M7 验收修复） |

---

## 8. 已知的"文档 vs 代码"偏差登记

以下偏差均已在对应契约中记录，本文按代码事实陈述：

1. **MinerU 未集成**（需求 §8 原选型）：pdf 走 PyMuPDF，OCR 走 VLM/PaddleOCR 三级次序；NOTICE 保留预留声明。
2. **Redis 未实际接入**：compose 提供可选服务，但 server 当前全部为进程内实现（Caffeine/令牌桶/内存 Token），`cache.provider` 切换属未来扩展。
3. **重建方式**（v1.9 已回补需求）：切分配置重建走同索引内原子替换，非"新索引+切别名"。
4. web / deploy 仓的 README、CHANGELOG 曾滞后于代码，已由四仓 `docs/oss-readiness` 分支的开源就绪 PR 清偿（server #19 / parser #4 / web #18 / deploy #22）。
