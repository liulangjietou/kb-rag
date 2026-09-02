# kb-rag 架构文档


> 版本：v2.7（基线 = v2.6 + M24 模型 Token 成本台账与租户配额，2026-08-14；v2.6 基线为 M23，其余历史见 Git）
> 日期：2026-08-14
> 作者：RichardFyoung / Claude
>
> **文档定位**：本文描述系统的实际实现架构（以代码为准），与以下文档互补——
> - `知识库需求文档.md`：需求与设计决策的唯一事实源（"为什么做、做什么"）
> - `M1~M24-CONTRACTS.md`：各里程碑的实现契约与已接受偏离（"每一期怎么做的"）
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
 ┌──────────────┐  REST + MCP     ┌▼──────────────────────────────────────┐
 │  智能体应用 /  │  (API Key /     │  kb-rag-server (Java 17 / Boot 3)      │ :20000
 │  MCP 客户端   │◄──Memory Key)──►│  kb-api → kb-app → kb-domain           │
 └──────────────┘  /api/v1/       │                 ▲                      │
                   knowledge/*    │                                        │
                   /api/v1/       │                                        │
                   memory/*       │                                        │
                                  │       kb-infrastructure ───────────────┤
                                  └──┬─────┬─────┬─────┬─────┬─────┬──────┘
                                     │     │     │     │     │     │ HTTP multipart
                                  MySQL   ES  Qdrant MinIO Neo4j ┌──▼───────────────┐
                                 :13306 :9200  :6333 :9000 :7687 │ kb-rag-parser     │ :20001
                                 (事实源)(BM25/  (向量, (原件/ (图,  │ (Python/FastAPI)  │
                                        lite向量) full) 归档) 可选) │ PyMuPDF/openpyxl  │
                                                                 └──────────────────┘
```

- **kb-rag-server**：唯一的业务中枢。管理台 API、对外开放 API（REST 与 MCP 双 transport，见 §3.9）、索引管线编排、检索链路、**全部大模型调用**（嵌入/重排/对话/视觉四类 Provider）。
- **kb-rag-parser**：纯解析微服务。只做文件解析、版面/扫描页判定、图片抽取与可选本地 OCR，**不调用任何大模型**（M3 契约 §0 定版的职责边界）。
- **kb-rag-web**：管理台前端，无 SSR、无状态库（React Context），通过 Vite 代理 / 反代与 server 同源交互。
- **kb-rag-deploy**：入口仓。docker-compose（lite/full/es-ik/graph）、OpenAPI 契约、跨仓文档、备份恢复与压测脚本、Demo 素材。

### 1.2 端口契约（M1 定版）

| 服务 | 端口 |
|---|---|
| kb-rag-server | 20000 |
| kb-rag-parser | 20001 |
| kb-rag-web（dev） | 20002 |
| MySQL / ES / MinIO / Qdrant / Redis / Neo4j | 13306 / 9200 / 9000+9001 / 6333+6334 / 6379 / 7687+7474 |

> MySQL 的 13306 是**宿主机映射端口**（避开本机默认 3306），容器内仍监听标准 3306；
> 其余中间件宿主机与容器端口一致。改端口只需调整 `.env` 的 `MYSQL_PORT`。

### 1.3 数据一致性原则

- **MySQL `t_kb_chunk` 是唯一事实源**；Qdrant 与 ES 均为派生索引，可从 MySQL 幂等重建。
- 双写状态按"分片 × 物理索引"粒度记录在 `t_kb_chunk_index_sync`，由定时补偿任务重放（§3.7）。
- **无消息队列、无强制 Redis**：异步全部落在进程内线程池上（多数经 Spring `@Async`；**自调用的提交点显式注入 Executor 手工 `execute`**——`@Async` 代理拦不住同 bean 自调用，标了也是内联跑，评测提交与门禁提交都属这一类，见 §3.7）；跨存储一致性 = 同步状态表 + 定时补偿。限流计数与上传 Token 为进程内实现，明确记录为单实例部署的降级方案（多实例扩展是未来边界，不影响功能正确性）；管理台登录 Token 自 M9 后修复起落库 `t_kb_auth_token`（V11，仅存 SHA-256 哈希，单实例重启不再踢掉全部会话）。

---

## 2. 部署形态

| 形态 | compose 文件 | 服务集 | 内存档 |
|---|---|---|---|
| **轻量（lite，默认引导）** | `docker-compose.lite.yml` | MySQL 8.0.36 + ES 8.11.4 + MinIO（+ Neo4j `profile=graph` 可选） | 8GB |
| **完整（full）** | `docker-compose.yml`（`include` lite，复用其服务定义） | lite 全部 + Qdrant v1.18.3（单容器，自带存储）+ Redis 7.2.5（`profile=redis,optional`） | 16GB |
| **ik 分词** | `docker-compose.es-ik.yml`（override 层） | 仅覆盖 elasticsearch 为自构建 `kb-rag-es-ik:8.11.4` 镜像 | — |

关键设计：

- **D16 双引擎切换**：`kb.vector.engine=es|qdrant`。lite 模式 ES 单引擎同时承担 BM25 与向量（dense_vector kNN）；full 模式 Qdrant 承担向量、ES 只做 BM25。两模式共用 `VectorStore` 抽象与同一检索链路，分数量纲由抽象层统一（§3.4）。
- **零 Key 启动**：不配任何模型 Key 也能完整跑通"上传→BM25 检索"。实现机制见 §3.3 的 `Unconfigured*Provider` 模式；检索自动降级 BM25 单路并透出 `degraded=[vector_route_unavailable]`。
- **Neo4j 可选**：`profile=graph` 默认不启动；`NEO4J_URI` 留空即注入 `DisabledGraphStore`，图能力整体禁用、其余功能零影响（含显式排除 `Neo4jAutoConfiguration`，防止 driver 在 classpath 上导致健康探针误报 DOWN）。
- **ik 词典热更新**：ES ik 插件远程词典指向 server 的免登录端点 `/internal/dict/ik/{ext|stop}.txt`（带 Last-Modified/ETag，约 60s 轮询）；词条以 DB（`t_kb_ik_dict`）为源，容器重建不丢。注意：切换 ik 镜像后存量索引 analyzer 仍是 standard，需触发重建才生效。

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
| **kb-domain** | 实体 + 端口 + 纯领域算法 | MyBatis-Plus 实体/Mapper（与 46 张业务表对应）、30+ 枚举、60+ 领域模型、**22 个出站端口接口**（`domain.port`）、无状态领域服务（切分/融合/指纹/评测指标/门禁裁决/标注迁移相似度等纯函数）、`KbProperties`（`@ConfigurationProperties(prefix="kb")`，二十余个嵌套段） |
| **kb-infrastructure** | 端口实现，按外部依赖分包 | `search.es` / `search.qdrant` / `graph` / `provider.{chat,embedding,rerank,vision}` / `connector` / `storage` / `parser` / `notify` / `web` / `auth`（LDAP/OIDC/SAML/CAS）/ `config` |
| **kb-app** | 应用编排层（架构主体） | 24 个业务域包：既有索引/检索/评测/开放平台等 23 域 + `modelusage` 用量编排 |
| **kb-api** | HTTP 边界与装配点 | 36 个 Controller、过滤器/拦截器、SSE、MCP 协议层（`api.mcp`，§3.9）、健康探针、`application.yml`、Flyway 脚本；`@SpringBootApplication(scanBasePackages="io.kbrag")` 为唯一装配点 |

分层规则：Controller 只依赖 kb-app 的 Service 与 kb-domain 的 model/enum；kb-app 只依赖端口接口，**从不依赖 kb-infrastructure 具体类**；kb-infrastructure 实现端口，与 kb-app 互不感知。

### 3.2 领域端口与实现（22 个）

| 端口 | 实现（默认 / 降级） | 说明 |
|---|---|---|
| `EmbeddingProvider` | `DashScopeEmbeddingProvider` / `UnconfiguredEmbeddingProvider` | `embed`、`dimension`（维度由 Provider 声明，固化进索引名嵌入段）、`maxBatchSize` |
| `RerankProvider` | `DashScopeRerankProvider` / `UnconfiguredRerankProvider` | 超时 1.5s 降级粗排 |
| `ChatProvider` | `DashScopeChatProvider` / `UnconfiguredChatProvider` | 改写/路由/生成/judge 共用 |
| `ChatProviderFactory` | `ModelChatProviderFactory` | 按模型名缓存实例——应用版本快照的 `chat_model` 经此生效 |
| `VisionProvider` | `DashScopeVisionProvider` / `UnconfiguredVisionProvider` | 图片文本代理（VLM 调用全部在 server 侧） |
| `VectorStore` | `EsVectorStore`（lite）/ `QdrantVectorStore`（full） | `ensureIndex/upsert/delete/updateEnabled/snapshotIndex/search`；分数统一见 §3.4 |
| `FulltextStore` | `EsFulltextStore` | BM25 检索 |
| `GraphStore` | `Neo4jGraphStore` / `DisabledGraphStore` | 实体/关系/溯源边；Neo4j 为派生存储可重建，无对应 MySQL 表 |
| `ObjectStorage` | `MinioObjectStorage` | 原件/解析产物/图片/审计归档；预签名 URL |
| `DocumentParserClient` | `HttpDocumentParserClient` | RestClient 调 parser 三端点（§4.4） |
| `WebhookNotifier` | `HttpWebhookNotifier` | 告警外发 |
| `TokenEstimator` | `SimpleTokenEstimator` | 分片长度以 token 计、`max_content_length` 预算 |
| `VersionPinChecker` | `AppVersionPinChecker` | 被应用版本可见集引用的文档版本禁止归档（已下线版本同样 pin——chunk 正文只在 MySQL） |
| `WebPageFetcher`（M12） | `HttpWebPageFetcher` | 网页抓取（URL 导入/增量同步）：SSRF 防护由调用侧经 kb-domain 的 `UrlGuard` 前置校验，Content-Type 白名单、体积上限、超时控制 |
| `MultimodalEmbeddingProvider`（M14） | `DashScopeMultimodalEmbeddingProvider` / `NoopMultimodalEmbeddingProvider` | 视觉理解整页索引与以图搜图的共用向量通道（DashScope multimodal-embedding-v1）；未配置时注入 Noop 实现，开关置灰、多模态路跳过并记 `mm_route_unavailable`；开启但多模态权重为 0 时跳过并记 `mm_route_skipped` |
| `ExternalConnector`（M14/M23） | `S3CompatibleConnector` / `ConfluenceCloudConnector` | 外部数据源 SPI：按 source_type 路由；S3/OSS 以 object key + ETag 增量，Confluence Cloud 以稳定 pageId + version 增量，两者均只把正文馈入普通上传链。连接器特定字段校验在登记/更新前 fast-fail |
| `DirectoryAuthenticator`（M15） | `LdapDirectoryAuthenticator` | 单点登录目录认证：裸 JNDI simple bind，不引入 Spring LDAP；目录账号首登自动建号 |
| `OidcClient`（M16） | `NimbusOidcClient` | OIDC 授权码模式：发现文档/code 换 token/JWKS 验签 |
| `SamlProcessor`（M16） | `XmlDsigSamlProcessor` | SAML 2.0 Response 签名验证（自实现 XML-DSig，不引入 Spring Security SAML） |
| `CasValidator`（M16） | `HttpCasValidator` | CAS ticket 服务端二次校验 |
| `MemoryStore`（M19） | `EsMemoryStore` | 记忆节点检索副本：单物理索引 `kb_memory_nodes_v1` 所有记忆库共用（隔离靠 `library_id`+`user_id` filter）；vector mapping 懒加载（首个带 embedding 的写入按维度 putMapping）；kNN+BM25 并联，零 Key 降级 BM25 单路；过期节点查询期过滤 |
| `ModelCallMeter`（M24） | `ModelUsageService` / 测试用 `NOOP` | Provider HTTP 出站前做租户月配额原子预占，响应后以供应商 usage 或保守估算结算；适配器不知道数据库细节 |

**零 Key / 能力开关统一装置**：`ModelProviderConfig` 是唯一读模型凭据的地方，凭据为空即注入 `Unconfigured*` 实现；`GraphStoreConfig`（NEO4J_URI 空 → `DisabledGraphStore`）与 `QdrantClientConfig`（QDRANT_URI 空 → 不建 client、不注册健康探针）镜像同一模式。上游代码只写 `isConfigured()/isEnabled()` 一个分支，全链路无 null 检查——这是需求 §5"防御式编程只做一处且高复用"的落地点。

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
| `VectorScoreNormalizer` | 跨引擎向量分统一：ES `(1+cos)/2` 先还原、Qdrant 原生 cosine，统一映射到 [0,1]；两实现共享一份"引擎一致性单测" |
| `FusionRouter` → `RrfFusion`/`WeightedFusion` | 加权融合先按候选集 min-max 归一化；开图路的库强制 RRF（`GraphFusionPolicy`——图关联度是第三种量纲） |
| `CrossKbRrfFusion` + `KbQuotaAllocator` | 跨库只用名次不用分数；rerank 候选预算是**全局总量**按权重配额切分（向下取整、余量归最高权重库） |
| `RerankService` | 候选上限 50（父子开启时联动 `max(50, top_n×5)`，硬上限 100）；超时 1.5s 降级粗排；rerank 分是唯一跨库跨路可比的绝对分；M14 新增 `rerank_mode=hybrid`：将语义重排分与原始融合分按 `rerank_w_semantic`（0-1，默认 0.7）线性加权，`semantic` 模式与旧行为一致 |
| `ParentChildMerger` | 召回/融合/rerank 全在子片粒度，之后按 parent_id 归并、父片得分取子片 max；目标父片数 `max(top_n×3, 20)` |
| `DisabledChildVisibility` + `ParentTextRedactor`（M9） | 含禁用子片的父片：默认整片返回并标注 `disabled_child_ids`；M9 起对偏移非 null 的禁用子片按 [start,end) **倒序**精确剔除文本段、置省略标记与 `redacted_child_count`；任一禁用子片偏移为 null 则整片回退（半剔除比不剔除更误导）；库级开关 `hide_parent_with_disabled_child` 打开则整父片隐藏 |
| `ScoreThresholdPolicy` | 阈值只作用于 rerank 分；降级时作用于标准 cosine 分；BM25 单路阈值失效并透出 `threshold_inactive`；`score_type` 必须如实上报 |
| `ActiveVersionResolver` | 版本可见集缓存（TTL 可配）+ 失效通知；快照路径绝不经过此处 |
| `EngineChunkCleaner` | 召回命中事实源已删分片时反向清理引擎（**快照路径关闭自愈**，防跨索引误删——M6 红线） |
| `OfflineExecutionContext` | ThreadLocal 离线评测标记：超时放宽至 10s、降级不计入生产监控 |

**强制过滤不变式**：各路召回必须在引擎侧过滤 `document_version_id ∈ 版本可见集` 且 `enabled=1`，链路强制、不可由参数关闭；图路回溯 chunk 后在 MySQL 事实源二次复核同一谓词（"图提议、MySQL 裁决"）。

**图路（M7）**：`GraphRetrievalService` 检索侧零 LLM——query 轻量切词（`GraphQueryTokenizer`）→ Neo4j 实体名 fulltext（cjk 分析器）匹配 → N 跳扩展（默认 2）→ 溯源边回 chunk，关联度 = 归一化匹配分 / (1+跳数)（`GraphRelevanceScorer`）；快照上下文图路直接关闭且不记降级（能力边界而非故障）。抽取侧 `GraphExtractionService` 逐分片 LLM 抽取 + 输出强校验（非法跳过计 `t_kb_task.skipped_count`）。

**degraded 枚举**（`DegradedReason`，与需求 §4.8 一一对应）：`query_rewrite_timeout/error/unavailable`、`rerank_timeout/error/unavailable`、`vector_route_unavailable`、`route_fallback_all`、`threshold_inactive`、`snapshot_index_missing`、`graph_route_unavailable`、`image_understanding_unavailable`（M9）、`mm_route_skipped`/`mm_route_unavailable`（M14：多模态开启但权重 0 跳过 / provider 未配置或失败）。

### 3.5 索引管线（`io.kbrag.app.document` + `io.kbrag.app.index`）

- **上传事务只持久化事实**（MinIO 原件 + document/version 行），管线经 `TransactionSynchronization.afterCommit` 提交异步执行——异步 worker 用独立连接读版本行，事务内触发会产生提交竞态（M1 联调发现的真实缺陷，全仓仅 `DocumentService` 与 `EngineChunkCleaner` 两处使用该手法）。
- `IndexPipelineService` 是管线主编排，五个 `@Async(INDEX_EXECUTOR)` 入口：`submit`（新上传）/ `submit(versionId, reuse)`（指纹复用）/ `submitRestore`（归档版重建）/ `submitRebuild`（配置重建）/ `submitConfirm`（预览确认后续跑）。
- 阶段：解析（parser HTTP）→ 清洗/脱敏（`DocumentCleaner`/`TextDesensitizer`；按页切分的库走 `PagedContentAssembler` 逐页清洗后拼回并记录页区间）→ 图片资产与 VLM 文本代理插回（`ImageAssetService`/`ImagePlaceholderResolver`，单图失败不失败整篇）→ 切分（`SplitterRouter` 策略路由，未知 code 回落定长；`page` 策略由管线直接分流到 `PageSplitter`，因它还需要页区间；父子两级切分 `ParentChildSplitter`，仅与定长策略组合）→ 嵌入（`ChunkEmbedder`，零 Key 全部 SKIPPED）→ 双写（`ChunkIndexWriter`：**先写 PENDING 同步行、再调引擎、再更新状态**——补偿扫描的唯一证据链）。
- **指纹复用**：`VersionFingerprintFactory` 产出 content_hash/解析指纹/分片指纹/嵌入版本，`VersionArtifactReuser` 在全匹配时复制上一构建的 chunk 行（新 ID、重写父链），跳过解析与切分。
- **版本机制**：`DocumentVersionPlanner` 一次回答重复判定/版本号（major.minor）/可否复用；`DocumentVersionActivator` 激活时旧 active 退回 READY（支持秒级回滚）；`VersionRetentionService` 异步清理超保留窗口（默认 3）的旧版 chunk（保留原件与解析产物）；`AppVersionPinChecker` 保护被快照引用的版本不被清理。
- **标注跨版本（M4a + M9）**：`AnnotationInheritanceService` 按 chunk_text_hash 精确继承禁用类标注（新版本不自动继承其他标注）；M9 起 `AnnotationMigrationAdvisor`（domain 纯函数）以归一化字符 3-gram **Dice 系数**（对称指标——刻意不用评测的非对称重叠率）为待复核标注懒计算迁移建议（阈值 `kb.annotation.migration-min-score=0.35`、top 3、候选限同文档当前激活版本、短文本不推荐），`POST /api/v1/annotations/{id}/migrate` 人工逐条确认（仅禁用/编辑两类，幂等；刻意不做自动与批量迁移——误迁移代价大于逐条确认成本）。

### 3.6 应用发布与开放 API（`appcenter` + `openapi`）

- `AppVersionService`：八状态机全部迁移收敛于 `transition` 一个方法，合法迁移定义在 `AppVersionStatus` 枚举上；"至多一个 RELEASED"由表上 `released_slot` 生成列 + 唯一索引双保险。
- `ReleaseGateService`（`GATE_EXECUTOR`，与评测池分离防自等待死锁）：同语料同时刻双跑（候选配置 vs 当前正式版配置，复用 `EvalRunService`）；`ReleaseGateJudge`（纯函数，唯一检索裁决点，含 1e-9 浮点余量）三态裁决；`GateMetricsRecomputer` 在双方共同判定的 case 交集上重算检索指标，堵分母漂移。M21 起应用版本显式开启 `answer_gate` 时，同一双跑继续经过 `AnswerGenerationService`（生产问答共用生成路径）与 `FinalAnswerJudgeService`，`FinalAnswerGateRecomputer` 只在双方结构化 Judge 均成功的 case 交集上重算，`FinalAnswerGateJudge` 再与检索结论按“非通过优先”合并；Judge 失败只产生 LOG_ONLY，不能伪装成质量回退。
- `AppReleaseSnapshotService`：门禁裁决后、RELEASED 生效前，**同时冻结** `index_snapshots`（`IndexSnapshotService`：ES `_clone` / Qdrant scroll 游标拷贝，不挂别名）与 `visible_version_ids`——只冻结索引不冻结可见集正是"回滚后召回全空"缺陷的根源（v1.6 修复）。
- 对外 API `/api/v1/knowledge/{search,chat}`：**独立的 `ApiKeyAuthFilter` servlet 过滤器链**（刻意不与管理台 Bearer 拦截器共用入口）；`ApiKeyFactory` 一把 Key 三形态（明文仅创建时返回一次 / SHA-256 digest 用于鉴权 / 前缀用于展示）；`ApiRateLimiter` 进程内令牌桶；`RequestOverridePolicy` 白名单只放 4 个响应形态参数（top_n/score_threshold/metadata_filter/max_content_length），越界拒绝而非忽略；`ApiAuditService` 异步落审计（拒绝也记录、401 不落 429 落）、`ApiAuditArchiveService` 定时归档 MinIO 后分批物理删除；`QueryDigestFactory` 对审计 query 无条件脱敏截断。
- chat SSE 事件契约：`message_delta`* → `references`（元素为与 search nodes 同构的 RetrievalNode）→ `done`（含 request_id/用量/degraded）或 `error`；生成模型取应用版本快照配置，经 `ChatProviderFactory` 生效；`ChatPromptAssembler` 固定分隔符包裹检索内容（注入防线①）。
- `AnswerGenerationService` 收口 `ChatProviderFactory` 解析、系统 Prompt 与消息装配：开放 chat、管理台预览和 M21 离线答案评测共用它，防止门禁测到一条线上永远不会执行的“近似 Prompt”。检索仍由调用方负责，评测因此能把配置矩阵实际召回的节点原样送入生成。

### 3.7 异步与定时（无 MQ 架构的支撑件）

线程池（`AsyncConfig`，统一 `TaskDecorator` 透传 requestId 与 M24 计量上下文到 worker 线程）：

**池形状只有一条规则：要么队列为 0，要么 core == max。** `ThreadPoolTaskExecutor` 只有在队列**满**之后才扩容到 max，所以"深队列 + 更大的 max"这个组合里的 max 永远到不了——写下的上限是个不会发生的数字，而后面每个读代码的人都会信它。这条已经踩过两次（索引池 `core=2,max=4` 挂 200 深队列常年只有 2；评测池 `core=2,max=6` 挂 50 深队列常年只有 2，而它自己的 javadoc 写着"6 个 run 并行"）。检索池与流式池的 0 队列是刻意的例外：没有队列可填，扩容到 max 是**第一件**发生的事，正是它们要的"先吸收突发、再拒绝"。其余每个池的 core 就是真实并发，只有一个数字要读，`AsyncConfigTest` 用反射遍历全部 `@Bean` 钉住这条规则，防止出现第三次。

| 池 | core/max/queue | 用途与设计理由 |
|---|---|---|
| `indexTaskExecutor` | `kb.index.concurrency`(4)/同/200 | 索引管线；刻意小 + 有界队列，防饿死控制台 |
| `retrievalTaskExecutor` | 4/16/**0** | 检索超时保护；队列为 0，宁可快速降级不排队 |
| `evalTaskExecutor` | M4b/M4c 异步化后修复起 6/6/50 | 评测 run 监督；6 = 一次提交的配置矩阵上限，低于它会把控制台呈现为"一个动作"的矩阵悄悄串行化。case 级并发另由 `kb.eval.concurrency` 控制 |
| `evalCaseTaskExecutor` | `kb.eval.concurrency`(4)/同/500 | 全部在跑评测的 case **全局**上限（非每 run）；CallerRuns 回压 |
| `gateTaskExecutor` | M4b/M4c 异步化后修复起 4/4/20 | 门禁双跑监督；**必须与评测池分离**，否则 gate 排在自己等待的 run 前面死锁。排队的 gate 不是"晚点跑"而是"发布卡住"——版本整段等待期都停在 `GATING` |
| `auditTaskExecutor` | 1/1/2000 | 审计异步写；异常只记日志绝不上抛（1 是它一直以来的真实并发，原 max=4 在 2000 深队列后从未达到） |
| `chatStreamTaskExecutor` | 2/16/**0** | SSE 流式生成；排队的流 = 客户端盯着没有首 token 的连接 |
| `extSourceTaskExecutor` | 1/1/100 | 外部源扫描；慢出站 I/O，突发排队而非并行扫桶 |
| `graphTaskExecutor` | `kb.graph.task-concurrency`(2)/同/50 | 图谱抽取，单任务小时级；与 `extract-concurrency` 相乘才是对话模型峰值 |
| `embedTaskExecutor` | `kb.embedding.concurrency`(4)/同/500 | 嵌入请求**全局**上限；CallerRuns 回压 |

**拒绝策略与提交处的义务**（M4b/M4c 异步化后修复起）：评测池与门禁池保留默认 `AbortPolicy`，不换 CallerRuns——把整条评测 run 拽回提交它的 HTTP 请求线程，恰恰是 PR #32 修掉的那个形态。代价是 `execute()` 会抛 `RejectedExecutionException`，而两处提交都发生在"状态已经落库之后"，所以兜底必须写在提交处：`EvalRunService` 把被拒的 run 就地改判 `FAILED` 并写明原因（否则留下一行没人推进的 `PENDING`，且同批前面的配置已落库，成半截提交）；`ReleaseGateService` 把被拒的门禁交给 `failGate` 记为 `LOG_ONLY/RUN_FAILED`（否则版本永久停在 `GATING`，而 `release` 入口恰好拒绝从 `GATING` 再次发布——自锁只能改库）。

**requestId 装饰器保存并恢复、而不是无条件 clear**（M4b/M4c 异步化后修复起）：`evalCaseTaskExecutor` 与 `embedTaskExecutor` 用 CallerRuns，队列满时任务回跑在提交者线程上，finally 里的 `MDC.remove` 会清掉**提交者自己**的 requestId——那条 run / 那次索引从队列填满的那一刻起后半段全部断链。改为记下运行前绑定的值再放回：worker 线程上本就没有绑定，恢复 null 即等于原本想做的 clear。

定时任务（`@Scheduled`，`SchedulingConfig` 单独持有 `@EnableScheduling` 便于单测排除）：

| 任务 | 触发 | 职责 |
|---|---|---|
| `IndexSyncCompensationService` | fixedDelay 30s | 扫 FAILED + 超时 PENDING 同步行，按物理索引分组幂等重放 |
| `EvalRunCompensationService` | fixedDelay 5min | 扫超过 `kb.eval.stuck-timeout-minutes`(120) 没动过的 PENDING/RUNNING 评测 run，改判 FAILED；**只改判不重跑**（`execute` 插 case 行前不清旧行，重跑会翻倍污染指标），且走 wrapper update 不走 `updateById`（不碰乐观锁版本，被早收的慢 run 自己那次写入仍能落地） |
| `AlertEvaluator` | fixedDelay 60s | 任务连续失败 / 降级率 / 双写积压三类触发 + 静默期 |
| `ApiAuditArchiveService` | cron 03:30 | 审计日志归档 MinIO → 分批物理删除 |
| `AppSnapshotRetentionService` | cron 04:15 | SUPERSEDED 版本快照按保留数清理（RELEASED 永不清理） |
| `WebSourceService` / `ExtSourceService` | cron 02:30 / 03:00 | 按源同步网页与外部连接器内容；模型消费显式归属知识库租户 |
| `ModelUsageService` | cron 每小时 05 分 | 将超时 RESERVED 预占保守结算为 estimated；乐观锁保证多实例重复扫描安全 |

通用持久化任务调度当前仍不投入：`t_kb_task` 是状态事实而非竞争消费队列，其他定时器在多实例下也没有
统一 lease/owner/heartbeat 语义。量化立项门槛和未来最小协议见 `DURABLE-SCHEDULING-DECISION.md`。

### 3.8 数据模型（46 张业务表，Flyway V1-V24）

全部 InnoDB，统一 `id / created_at / updated_at / lock_version / deleted`，审计字段由 `AuditFieldFiller` 填充，业务 ID 带类型前缀（kb/doc/dv/ck/task/img/upt/an/evds/evc/evr/evre/app/av/ak/aud/smp/rfb/si/ws/wcred/exts/usr/role/tnt/opa/ml/mfr/mpr/mn/mak）。

| 迁移 | 表 / 变更 |
|---|---|
| V1（M1 基线） | `t_kb_knowledge_base`、`t_kb_document`、`t_kb_document_version`、`t_kb_chunk`、`t_kb_chunk_index_sync`、`t_kb_index_registry`、`t_kb_task`、`t_kb_admin_user`、`t_kb_system_config`、`t_kb_login_audit` |
| V2（M2） | `t_kb_ik_dict`；知识库加 `retrieval_config` |
| V3（M3） | `t_kb_image_asset`；文档加 `source_key`（聊天逻辑文档标识） |
| V4（M4a） | `t_kb_annotation`（幂等键 + inherit_status） |
| V5（M4b） | `t_kb_eval_dataset` / `t_kb_eval_case` / `t_kb_eval_run` / `t_kb_eval_result` |
| V6（M4c） | `t_kb_app` / `t_kb_app_version`（`released_slot` 生成列唯一约束）/ `t_kb_api_key` / `t_kb_api_audit_log`。`t_kb_app_version` **刻意不带 `tenant_id`**：从属表经 `app_id` 归属租户，其隔离由 `AppVersionGuard` 在 `AppVersionService#require` 背后解析根表完成，不靠行级围栏（见 §7.2 与 M16 契约 §1.3.2） |
| V7（M6） | 应用版本加 `visible_version_ids` + `index_snapshots` |
| V8（M7） | `t_kb_task` 加 `skipped_count`（图抽取跳过计数） |
| V9（M8） | `t_kb_source_mapping`（映射档案，启动播种内置模板、只补缺不覆盖） |
| V10（M9） | `t_kb_chunk` 加 `parent_start_offset` / `parent_end_offset`（子片在父片中的 [起,止) 字符偏移，切分副产物、不做事后反查；子片编辑/合并/拆分及父片编辑时失效置 null） |
| V11（M9 后修复） | `t_kb_auth_token`（管理台登录 Token 落库，仅存 SHA-256 哈希，24h TTL 语义不变） |
| V12（M10） | `t_kb_retrieval_feedback`（检索反馈，带幂等键）/ `t_kb_search_insight`（检索洞察埋点，只增不改） |
| V13（M11） | `t_kb_document` 加 `publish_status` / `review_note` / `effective_at` / `expires_at` / `trashed` / `trashed_at`；`t_kb_knowledge_base` 加 `review_required` |
| V14（M12） | `t_kb_web_source`（网页来源登记：URL/content_hash/四态同步状态/派生文档关联）。**刻意不带 `tenant_id`**：从属表经 `kb_id` 归属租户，其隔离由 `WebSourceGuard` 在每个入口解析根表完成，不靠行级围栏（见 §7.2 与 M16 契约 §1.3.2） |
| V15（M14） | `t_kb_ext_source`（外部对象存储源登记：端点/桶/前缀/凭证，secret_key 从不回读、库内 name 唯一）/ `t_kb_ext_source_item`（逐对象同步结果：object_key_hash 去重、etag 未变检查、四态 last_status，弱绑定于派生文档） |
| V16（M15） | `t_kb_role` / `t_kb_permission` / `t_kb_user_role` / `t_kb_role_permission` / `t_kb_role_kb`（角色→知识库数据范围）；`t_kb_admin_user` 增 `user_id`/`display_name`/`email`/`source`/`status`，`password_hash` 改可空；内置 5 角色与 18 权限码随迁移落库，存量账号提为 SUPER_ADMIN |
| V17（M16） | `t_kb_tenant`（内置默认租户种子化）/ `t_kb_doc_acl` / `t_kb_operation_audit`（含 username 冗余列）；6 张根聚合表增 `tenant_id`（存量行靠列 DEFAULT 划入默认租户）；`t_kb_document` 增 `visibility`、`t_kb_user_role` 增 `granted_by`、`t_kb_retrieval_feedback` 增 `channel`/`end_user_id` |
| V18（M17） | `t_kb_web_source` 增 `render_js`（JS 渲染抓取开关，默认 0 静态抓取） |
| V19（M18） | `t_kb_web_credential`（站点级认证凭据；BASIC/HEADER 两类，secret 刻意明文存储、读接口永不回传）。**建表时 host 全局唯一，V22 已收缩为租户内唯一** |
| V20（M19） | 记忆库 6 张表：`t_kb_memory_library` / `t_kb_memory_fragment_rule`（instruction_type/auto_update/expire_days/extract_version/builtin）/ `t_kb_memory_profile_rule`（fields 整体存 JSON 数组）/ `t_kb_memory_node`（idx_library_user）/ `t_kb_memory_profile`（uk_rule_user 唯一键 upsert）/ `t_kb_memory_app_key`（明文 `kb-mk-*`，只存 SHA-256 摘要 + 展示前缀）；权限种子 `memory:read`/`memory:write` |
| V21（M19 后修复） | `t_kb_memory_library` 增 `tenant_id`（存量行靠列 DEFAULT 划入默认租户）+ `idx_tenant` —— V20 建表时漏了 M16 的租户层，多租户部署下任何租户持 `memory:read` 即可列出全部署记忆库、`memory:write` 可改删他人的库与 Memory Key。记忆库是 memory 域的根聚合表，五张从属表（片段/画像规则、节点、画像、Key）经 `library_id` 归属租户，故只加这一列；配套把它加进 `KbTenantLineHandler.FENCED_TABLES`，并由 `MemoryLibraryGuard` 让每个管理端入口先解析库（见 §7.2） |
| V22（M18 后修复） | `t_kb_web_credential` 增 `tenant_id`（存量行靠列 DEFAULT 划入默认租户），`uk_host(host)` 收缩为 `uk_tenant_host(tenant_id, host)` —— V19 建表时漏了 M16 的租户层。缺陷两面：管理面任何租户持 `system:config` 可改删停用他人凭据；抓取面凭据按 host 全局查找，B 租户给自己的 WebSource 登记一个同 host URL，夜里的同步就会把 A 租户的密码发到那个请求上。配套把表加进 `KbTenantLineHandler.FENCED_TABLES`（只覆盖管理面），抓取面由 `WebCredentialService#resolveFor(tenantId, host)` 的显式租户谓词覆盖 —— 同步跑在无主体线程上，围栏在那条线程整条跳过（见 §7.2 与 M16 契约 §1.3） |
| V23（M21） | 最终答案评测字段：`t_kb_eval_case.expected_refusal`；`t_kb_eval_run` 增应用配置快照、答案 Judge 身份与聚合指标；`t_kb_eval_result` 增生成答案、生成耗时、五维评分、答/拒结果与失败原因。全部新列可空或有兼容默认值，存量 run 不回填 |
| V24（M24） | `t_kb_tenant.monthly_token_quota`（0=不限）；`t_kb_model_usage_monthly`（租户+月份原子 used/reserved 计数器）；`t_kb_model_usage`（不含客户内容的调用台账与价格快照）；`t_kb_model_price`（provider+capability+model 唯一价格） |

引擎侧可过滤字段全集（Qdrant 标量 / ES filter，建索引时显式声明）：`kb_id`、`doc_id`、`document_version_id`、`enabled`、`parent_id`、`chunk_type`、`tag_ids`、`session_id`、`sender`、`msg_time`、`chunk_seq`；其余 metadata 只存 MySQL。新增可过滤维度视为 schema 变更，走索引重建迁移。

### 3.9 MCP 双协议层（M20 / M22）

知识库应用与记忆库的开放能力在 REST 之外提供 **MCP（Model Context Protocol）** 第二种 transport：任何 MCP 兼容客户端配一个 URL 加一把既有 Key 即可接入。M22 在 M20 工具与身份不变的基础上，让同一 URL 同时服务 `2026-07-28` 逐请求元数据协议和旧版 initialize 协议。

| 端点 | server name | 工具集 | 凭证与过滤器链 |
|---|---|---|---|
| `POST /api/v1/knowledge/mcp` | `kb-rag-knowledge` | `knowledge_search` / `knowledge_chat` | `Bearer kb-sk-*`，`ApiKeyAuthFilter`（鉴权、app_scope、令牌桶限流、调用审计全部复用） |
| `POST /api/v1/memory/mcp` | `kb-rag-memory` | `memory_add` / `memory_search` / `memory_list` / `memory_update` / `memory_delete` / `memory_get_profile` | `Bearer kb-mk-*`，`MemoryKeyAuthFilter`（鉴权、库绑定、限流全部复用） |

- **`McpServerEngine`（手写零 SDK 依赖）**：按当前请求自识别时代。现代版方法表为 `server/discover` / `ping` / `tools/list` / `tools/call`；旧版保留 `initialize` / `notifications/*`。现代请求逐次校验 `_meta` 与 `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name`，不从上一个请求推断能力。
- **无状态设计**：不签发也不要求 `Mcp-Session-Id`；身份和协议版本都在每个 POST 内自足。现代成功 result 必含 `resultType=complete` 和 serverInfo；discover 与工具目录给出 5 分钟 public 缓存提示，现代目录按名称稳定排序。
- **状态语义按时代隔离**：现代头体不一致为 HTTP 400/-32020、版本不支持为 400/-32022、方法未实现为 404/-32601；旧版协议错误继续由 HTTP 200 body 承载。业务失败在两时代都只进入 `tools/call.result.isError=true`，不抛成协议错误。
- **Origin 前置校验**：`McpOriginValidationFilter` 位于两条 Key 鉴权过滤器之前。无 Origin 的服务间客户端放行；浏览器 Origin 必须命中既有 `CORS_ALLOWED_ORIGINS`，否则 403。来源规则只有这一份，不另造 MCP 白名单。
- **成功结果双形态**：`content[0].text`（JSON 文本，给只读 text 的老客户端）+ `structuredContent`（同 REST `data` 结构）同源产出。
- **鉴权：第二种 transport，不是第二种身份**：通过 Origin 后仍进入既有 Key 鉴权/限流/审计管线；Controller 只从 request attribute 读 principal。记忆库操作的库来自 Key 绑定关系，arguments 无法指定 library_id，越权继续 404。
- **参数绑定**：`McpArgumentBinder` 用 Jackson `treeToValue` + jakarta Validator 显式校验（tree 转换不触发 bean validation，手动补上这一刀）；知识库两工具复用 REST 的 `KnowledgeCallRequest`，记忆库 list/update/delete/profile 的 GET/path 形态参数收敛为 Controller 内部 record。`knowledge_chat` 带 `stream: true` 直接 INVALID_PARAM 并指路 REST SSE。

---

## 4. 解析服务架构（kb-rag-parser / kb-rag-parse-java）

### 4.1 技术栈与定位

解析服务有两套**行为等价、二选一部署**的实现，监听同一端口、同一套契约与环境变量，`PARSER_BASE_URL` 指向哪个都不需要改 kb-rag-server：

| 实现 | 技术栈 | 解析库 |
|---|---|---|
| `kb-rag-parser`（契约的原始定义方与行为基准） | Python 3.11+ / FastAPI + Uvicorn / pydantic 2 | **PyMuPDF**（pdf）、python-docx、openpyxl、标准库 csv/html.parser；可选 `requirements-ocr.txt`（paddlepaddle 3.3.1 + paddleocr 3.3.3，默认不装） |
| `kb-rag-parse-java` | Java 17 / Spring Boot 3.3.9（与 kb-rag-server 同版本线） | **Apache PDFBox**（pdf）、Apache POI（docx/xlsx）、jsoup（html）、Commons CSV、SnakeYAML；可选 Maven profile `ocr`（tess4j，默认不构建） |

两者以 `kb-rag-parse-java/tools/crosscheck.py` 端到端对拍，2026-09-02 实测 42/42 一致（含 pdf 文本层、图片去重、markdown 表格、时间戳归一、空白语义、聊天消息逐字段全等）。差异项与理由记在 `kb-rag-parse-java/README.md`「与契约的偏离说明」，其中唯一涉及契约文本的是 `ocr_source` 取值（Java 侧为 `tesseract` 而非 `paddle`，因 PaddleOCR 无 JVM 绑定；kb-rag-server 判的是该标记存不存在而非等于什么，故契约仍成立，但 `docs/openapi/kb-parser.yaml` 的枚举若要严格化需相应放宽——待 Owner 决策）。

> **许可差异（选型时的实际决定因素）**：`kb-rag-parser` 的 pdf 路径依赖 PyMuPDF，其免费分发一侧为 AGPL-3.0，带网络服务条款——以网络服务形式提供包含该代码的程序这一行为本身即触发完整对应源代码的提供义务，而解析服务恰是网络服务；这一点在该仓 README/NOTICE 中作为待 Owner 决策事项如实记录。`kb-rag-parse-java` 的 pdf 路径走 Apache-2.0 的 PDFBox，直接依赖全部为 Apache-2.0 / MIT / BSD-3-Clause，不触发此类义务。

> 注意：需求文档 §8 技术栈中"MinerU（pdf）"为原始选型，**实际实现未集成 MinerU**（全仓无引用；NOTICE 中为预留声明"does NOT use MinerU in M1"）。当前 pdf 路线 = 文本层抽取 + 扫描页 150dpi 渲染（Python 侧用 PyMuPDF，Java 侧用 PDFBox），OCR 由 server 侧 VLM 或解析服务的本地引擎承担。

### 4.2 API（统一信封 `{code, data, message, request_id}`，`code ∈ {OK, PARSE_FAILED}`）

| 端点 | 用途 |
|---|---|
| `GET /health` | 存活探针 |
| `POST /api/v1/parse` | multipart `file` + `file_ext`（pdf/docx/txt/md/xlsx/csv/html/htm，html 为 M12 增量）→ markdown + 按页文本与该页 markdown 切片（`scanned`/`ocr_source` 标记，`markdown` 为 M14 增量）+ 图片 base64（`kind=embedded|page_render`）+ `warnings[]` |
| `POST /api/v1/parse/chat` | multipart `file` + `file_ext`（csv/xlsx/txt/html）+ 可选 `mapping_profile`/`profile_yaml` → 统一 ChatMessage 会话结构 + `skipped` 统计 |

### 4.3 模块结构

以下按 `kb-rag-parser`（Python）描述，它是契约的原始定义方。`kb-rag-parse-java` 逐模块一一对应（`parsers/` -> `parser/`、`chat/` -> `chat/`、`ocr/` -> `ocr/`、`security.py` -> `security/`、`config.py` -> `config/`），职责划分与 fast-fail 边界相同，逐项对照见 `kb-rag-parse-java/README.md`「项目结构」。

- `main.py`：端点 + `ThreadPoolExecutor(4)` 跑阻塞解析 + `asyncio.wait_for` 300s 超时；所有可恢复失败归一化为信封错误。
- `config.py`：全部常量单点（100MB 文件上限、zip 解压 500MB/2000 条目上限、扫描页文本阈值、图片 100 张/10MB 上限、TXT 不匹配行 30% 失败线等）。
- `parsers/`：`BaseParser` 策略接口 + `registry.py` 查表分派；pdf（扫描页判定 + 渲染，扫描页不再另抽内嵌图防双计）/ docx（段落表格图片按原始顺序，占位符插回）/ excel+csv（markdown 表格，每 sheet 一个 page_no）/ text / html（M12 通用 HTML 页面：仅标准库 html.parser，标题/块级分段→markdown，零出站请求）。
- `chat/`：`parser.py` 按格式编排；`mapping.py`（MappingProfile：csv/xlsx 候选列名 + txt 行正则 + html 选择器三段配置，来源优先级 请求内联 profile_yaml > 本地 mappings/*.yml > 按扩展名内置默认）；`txt_adapter.py`（行首正则 + 续行归并）；`html_adapter.py`（仅标准库的最小 DOM 选择器引擎，剥 script/style，img 只判存在不下载）；`normalize.py`（时间戳/消息类型归一）。语音/视频消息剔除并计入 `skipped`。
- `ocr/engine.py`：`OcrEngine` 策略（NoOp / Paddle 懒加载），`OCR_ENGINE=paddle` 未装依赖时启动 fast-fail；单页 30s 超时、按页降级绝不整篇失败。三级次序：**server 侧 VLM（有 Key）→ 本地 PaddleOCR（离线）→ 跳过**；本地 OCR 成功回填 `pages[].text` 并置 `ocr_source=paddle`，server 据此跳过 VLM。
- `security.py`：zip-slip/zip 炸弹校验、defusedxml 全局加固（XXE）；解析阶段无任何出站网络请求（SSRF 基线）。
- `encoding.py`：utf-8-sig → utf-8 → gbk 探测，`errors=replace` 兜底永不抛。

### 4.4 与 server 的交互

server 侧 `HttpDocumentParserClient`（`kb-infrastructure`）经 `PARSER_BASE_URL` 调用，指向哪一套实现都不需要改动 server；`X-Request-Id` 头透传实现两侧日志串联；M3 起新增字段全部防御式读取，双向兼容任意部署顺序；调用方仅 `IndexPipelineService` 与 `ChatImportService`（经 `DocumentParserClient` 端口）。

---

## 5. kb-rag-web 架构

### 5.1 技术栈

Vite 8 + React 18 + TypeScript 6 + Ant Design 5 + react-router-dom 6 + axios；lint 用 oxlint。无状态库（React Context：`AuthContext` / `ModelStatusContext`）。dev 端口 20002，`/api` 与 `/actuator` 代理到 20000。

### 5.2 路由与页面

守卫嵌套 `RequireAuth → RequirePasswordChanged → AuthenticatedShell(ModelStatusProvider + MainLayout)`：

| 路由 | 页面 |
|---|---|
| `/login`、`/change-password` | 登录（防爆破提示）、首登强制改密 |
| `/kb`、`/kb/:kbId` | 知识库列表；详情（文档上传/状态轮询/审核与有效期操作/分片标注 ChunkDrawer/版本 VersionDrawer/索引配置/聊天导入向导；图谱 / 反馈 / 洞察 / 回收站 / 网页导入 / S3 与 Confluence 外部数据源等 Tab） |
| `/search` | 检索调试（参数面板、分数明细、degraded 告警、收进评测集） |
| `/chat` | 问答调试（JWT 走 `/apps/{id}/chat-preview` SSE） |
| `/apps`、`/apps/:appId` | 应用中心（配置 / 版本与门禁 / API 调试三 tab） |
| `/memory`、`/memory/:libraryId` | 记忆库（M19，`memory:read` 可见）：库列表；详情五 Tab（片段规则 / 画像规则 / 记忆数据 / 检索调试 / Memory Key） |
| `/mcp` | MCP 调试（M20/M22，`app:read` 或 `memory:read` 任一可见）：端点与协议时代二选一（切换即清场）→ 现代 server/discover 或旧版 initialize → tools/list / tools/call；现代请求自动生成 `_meta` 与镜像头，响应区区分 HTTP、JSON-RPC、isError 三个平面，并生成等价 curl 与客户端配置 |
| `/eval` | 评测中心（数据集 / case 标注 / 证据复核 / 运行报告四 tab） |
| `/settings` | 系统设置（模型状态 / ik 词典 / API Key / 审计 / 告警 / 导入映射） |

### 5.3 与后端的四条通道

1. `api/request.ts`：axios 主通道，`baseURL=/api/v1`，Bearer 注入、401 统一跳登录、`unwrap` 拆信封。
2. `api/chatStream.ts`：SSE 驱动用原生 fetch（刻意绕过 401 拦截器），同时服务问答调试（JWT）与 API 调试 tab（API Key）。
3. `api/publicApi.ts`：API Key 直连对外端点，429 读 Retry-After。
4. `api/mcp.ts`（M20）：MCP 调试页仿 publicApi.ts 绕过共享 axios 实例直连 fetch——粘贴的 Key 不是管理台 JWT，401/429 正是页面要观察的对象。

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
| `docs/` | 需求文档、M1-M24 契约、OpenAPI（`kb-server.yaml` 0.26.0-m24 / `kb-parser.yaml` 0.12.0-m12）、持久化调度投入决策、备份恢复手册、本文档与 `FLOWS.md`；调用方接入文档另见主仓 `docs/MCP接入指南.md` |

---

## 7. 横切关注点

### 7.1 配置五层模型

①环境变量/yml（`KbProperties`，全部带本地默认值）→ ②全局系统设置（`t_kb_system_config`，Provider/Webhook 等）→ ③知识库级（`index_config`/`retrieval_config` JSON）→ ④应用版本快照（发布固化，不再回落全局）→ ⑤API 请求级覆盖（白名单 4 参数）。检索侧三层合并在 `RetrievalSettings`（请求 > 库默认 > 部署默认）。

### 7.2 安全

- **三条独立鉴权链**：管理台 Bearer Token（`AuthInterceptor`，Token 哈希落库 `t_kb_auth_token`、防爆破锁定、首登随机密码强制改密；M15 起叠加 `PermissionInterceptor` 功能权限 + 知识库数据范围两层授权）、对外 API Key（`ApiKeyAuthFilter` 独立过滤器链、哈希存储、app_scope 授权范围、令牌桶限流）、记忆库 Memory Key（M19，`MemoryKeyAuthFilter`：`Bearer kb-mk-*` 只作用于 `/api/v1/memory/**`，一把 Key 绑定一个记忆库、库内再按 user_id 隔离，隔离是查询谓词、越权一律 404；限流复用 `ApiRateLimiter`）——三面凭据形态、失败面、限流口径互不干扰。
- **记忆库的第三层隔离是租户**（V21 修复）：Key 绑定库（应用级）与 `user_id`（实体级）只覆盖开放端，管理端的 `memory:read`/`memory:write` 只回答"这个账号能不能碰记忆库"，回答不了"能碰哪些"。补法与知识库同构：`t_kb_memory_library` 加 `tenant_id` 并进围栏，五张从属表不加列、经 `library_id` 归属；关键是 `MemoryLibraryGuard` —— 管理端带 `libraryId` 的 21 个入口**一律先解析库**（包括按 rule_id / node_id / key_id 直接寻址的那些），否则从属语句压根不经过带 `tenant_id` 的那张表，围栏形同虚设；余下 2 个（库列表、建库）无 libraryId，由围栏本体覆盖（SELECT 拼条件 / INSERT 注入）。开放端不受影响：那条链上没有控制台主体，`ignoreTable` 整条跳过，这是必须保留的既有语义（一拼租户条件，Key 会把自己的库过滤掉）。
- **站点凭据的租户隔离要两套机制，因为它有两类读者**（V22 修复）：控制台增删改查靠行级围栏（`t_kb_web_credential` 进 `FENCED_TABLES`），夜间网页同步靠 `WebCredentialService#resolveFor(tenantId, host)` 的显式租户谓词——同步跑在 `@Scheduled` 线程上，没有控制台主体，围栏在那条线程整条跳过，光进名单等于抓取面零防护。租户由 `WebSource.kb_id` 反查知识库得到；库被删的孤儿登记解析不出租户，按"无凭据"匿名抓取，**绝不退化成按 host 查**。连带两处语义收缩：同 host 凭据从全局唯一变租户内唯一（两个租户各在同一 wiki 上放一个只读账号是正常业务），"一次 401 就停掉该站点本轮抓取"的去重键从 `host` 变为 `(租户, host)`（锁的是账号，而两个租户在同一 host 上是两个账号，按 host 记会让一家的过期密码掐掉所有人的当晚抓取）。详见 M16 契约 §1.3 与 §1.3.1。
- **从属表的隔离是"每个入口先解析根"，不是"表在不在围栏名单里"**（V22 后修复，M12/M17/M18 网页源）：`t_kb_web_source` 不带 `tenant_id`、也不在 `FENCED_TABLES` 里，这个设计是对的（经 `kb_id` 归属租户，加列只会造第二个可以不一致的事实源）；错的是四个入口——按 `sourceId` 的手动同步 / 改开关 / 硬删、按 `kbId` 的列表——压根不查 `t_kb_knowledge_base`，围栏在那几条语句上什么都没做。新增 `WebSourceGuard` 让四个入口一律先解析到根表（跨租户读作"不存在"→ **404**），与记忆库的 `MemoryLibraryGuard` 同构。**根因值得单独记住**：原先站在这些入口前面的 `KbScopeGuard#requireWebSourceAccess` 回答的是"库在不在调用者的数据范围里"，一行租户判断都没有，且第一行的 `unrestrictedKbScope()` 短路对租户 SUPER_ADMIN、未配数据范围的 KB_ADMIN 直接放行——**只覆盖数据范围的守卫比没有守卫更危险，它让 review 以为这条路径已经守住了**，该方法已随本次修复删除。判定顺序也是契约的一部分：租户（404）先于数据范围（403），反过来会用状态码差异泄露"这个 id 在别的租户里存在"。详见 M16 契约 §1.3.2。
  > **后续普查发现这不是孤例，是一族**：同类的另外 8 个方法（document / chunk / annotation / dataset / case / run / ext-source / feedback）逐字同构，站在 43 个控制台端点前面，包括覆写外部数据源 AK/SK、用别家凭据发外网探测请求、硬删登记、清除别家文档、把别家文档回滚到旧版本。M16 后修复把 `KbScopeGuard` 整体重命名为 `KbResourceGuard` 并让 9 个方法一律先解析围栏根表。**顺带钉住一条更普适的教训**：`requireDatasetAccess` 查的 `t_kb_eval_dataset` 本来就在围栏名单里，短路却让那条语句根本不执行——围栏只保护它实际发出的语句，任何提前 `return` 都会连同已写好的围栏一起跳过，而这种失败在方法体里看不出来，因为围栏是拦截器。
  > **另一半是「入口自带 `kb_id` 却仍未解析」**：这类最容易被误判为安全，因为路径里那个 `kbId` 看着就是作用域本身——可它是**调用方声明的**作用域，不是被证实的。文档列表、回收站、检索洞察与统计、批量删除与重建、批量确认、全库重建与状态、文档密级读写共 15 个入口原先只有 Controller 里那行数据范围调用，链路上一次 `t_kb_knowledge_base` 查询都没有，报一个别家的 `kbId` 就能把按 `kb_id` 过滤的语句照常跑完。修复一律落在服务层方法首行（服务方法是所有调用方的必经之路），并把 28 处 Controller 的数据范围调用换成 `KbResourceGuard#requireKb`，**判定顺序由此在全域统一**。文档密级那两条另有一层教训：原先只校验「文档挂在这个 `kbId` 下」，跨租户调用方把别家的 `kbId` 与该库下的 `docId` 一起传进来完全对得上——**「从属行属于这个父」和「这个父属于你」是两个问题**。判据：这条链路上有没有一次对根表的查询？没有 → 未守，`kbId` 在路径里也一样。
- **收口点越靠近数据，新入口自动继承的概率越高**（M4c 后修复，应用版本）：`t_kb_app_version` 同样是不带 `tenant_id` 的从属表（经 `app_id` 归属 `t_kb_app`），而 `/app-versions/{vid}` 的五个端点只有功能权限码——任何租户持 `app:release` 就能**发布或回滚别家的应用版本**，直接改变别人对外 API 被服务的内容；持 `app:read` 就能读它的配置快照；发布还会在门禁执行器上对别家知识库启动同语料双跑，花掉他们的检索与模型调用。补法与前三处同构，但守卫落点不同：`AppVersionGuard` 放在 `AppVersionService#require` **背后**而不是各入口前面，因为那个方法是 11 处调用方的唯一入口（本服务自调用 5、`ReleaseGateService` 5、控制台预览 1），放入口必漏。**404 收口要连措辞一起收**：跨租户与"版本不存在"共用同一错误码同一文案、文案不含 `appId`，第二跳报成 `APP_NOT_FOUND` 就等于用错误码差异告诉调用方"你猜的 id 是真的、只是在别人那里"。对外 `search`/`chat` 走 `resolveForCall` 不经该方法、由 API Key 的 `app_scope` 把关，门禁执行器与预览流线程无控制台主体、围栏本就整条跳过，两者行为均零变化。详见 M16 契约 §1.3.2。
- **围栏放行分支的代价不是隔离，是可辨识性**（M15/M16 后修复，角色列表）：`t_kb_admin_user` 与 `t_kb_role` 是 `OPERATOR_UNFENCED_TABLES` 的两张表，持 `tenant:manage` 的平台运维读它们不带租户条件——这个放行是必需的（不放行，新建的租户就没人能给它建首个账号、授首个角色），也是安全的（写侧仍各自解析）。漏的是**返回体让运维认不出自己在看谁家的行**：`TenantService#copyBuiltinRoles` 给每个新租户照抄五个内置角色，`SUPER_ADMIN`/`KB_ADMIN`/`EDITOR`/`REVIEWER`/`VIEWER` 于是每户一份、`code` 与 `name` 全同，而 `RoleResponse` 只有 `role_id` 能区分——控制台角色管理页因此呈现为一张"每个内置角色重复 N 遍"的表，编辑哪一行全凭运气。补 `tenant_id` 并在持 `tenant:manage` 时多渲染一列"所属租户"。**判据**：凡是给某类调用方开了跨租户可见的口子，就要检查返回体里有没有一个字段能把看到的行归属回去；隔离做对了、可辨识性没做，用户侧的观感是"系统出了重复数据"，而运维的下一步操作是在猜。用户表本来就带 `tenant_id`（M16 随移户功能一并给了列），角色表是同一条规则漏掉的那一半。
- **MCP 是第二种 transport，不是第二种身份**（M20/M22）：两个 MCP 路径先经专用 Origin 白名单过滤，再进入既有 `kb-sk-*` / `kb-mk-*` 鉴权、授权范围、限流与审计管线；无新增凭据面。旧版协议错误保持 HTTP 200，现代版按规范使用 400/404，401/429 仍为 REST 信封（见 §3.9）。
- **Prompt 注入四防线**：①生成/judge prompt 固定分隔符包裹资料原文；②LLM 切分输出强校验（非法降级按长度切）；③路由白名单交集裁决；④改写结果仅作检索词。
- 解析侧基线：magic number 校验、zip-slip/炸弹、XXE（defusedxml）、SSRF（解析期零出站）；前端零 `dangerouslySetInnerHTML`；聊天导入默认脱敏（手机号/身份证/银行卡 16-19 位）；审计 query 无条件脱敏；MinIO 私有桶 + 限时预签名 URL。

### 7.3 可观测

- `request_id` 全链路：`RequestIdFilter` 入口生成 → MDC → 响应头 → `X-Request-Id` 透传 parser 与 Provider 调用日志 → `TaskDecorator` 带入异步线程。
- 健康：Actuator 组合探针（ES 必选；Qdrant/Neo4j 仅配置时注册；MinIO；parser `/health`）。Actuator 默认运行在独立回环监听器 `127.0.0.1:20003`，与业务端口分离；health 只返回聚合状态，不暴露组件名称和错误详情。
- 指标（M13）：`/actuator/prometheus` 从独立管理端口抓取（micrometer-registry-prometheus）；`KbMetrics` 门面承载四类业务指标——`kb_search_seconds`（Timer，source/zero_hit/degraded 标签）、`kb_task_completed_total`、`kb_openapi_rejected_total`、`kb_websource_sync_total`；`TaskBacklogMetrics` 提供 `kb_task_backlog`（pending/running 两支 gauge，DB 异常回 NaN 不失败抓取）；埋点全部骑在既有横切点上，记录失败绝不影响业务路径。远程抓取需要显式开放管理地址，并由防火墙或带认证的反向代理限制来源。
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
