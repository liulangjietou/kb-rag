# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。
标注 `[schema]` 的条目包含数据库迁移脚本，升级时会自动执行 Flyway。

## [未发布]

尚未打过 tag，以下条目全部属于首个发布版本的内容，按里程碑倒序排列。

### 变更（M9 之后）

- **full 模式向量引擎定为 Qdrant（不兼容变更）**：`VectorEngine` 枚举取值为 `ES` / `QDRANT`，
  向量路由由 `QdrantVectorStore` 承担——走 Qdrant REST API，复用 `spring-boot-starter-web` 已有的
  `RestClient`，不引入 gRPC/protobuf 依赖。配置项为 `kb.qdrant.uri` / `kb.qdrant.api-key`
  （环境变量 `QDRANT_URI` / `QDRANT_API_KEY`）
- 分片启用开关在向量引擎侧真正生效：`updateEnabled` 通过 Qdrant 的 set payload 原地翻转标记，
  既不触碰向量、也不需要重新嵌入，被禁用的分片不再占用召回预算（此前只靠 MySQL 事实源在
  检索后过滤）

### 修复（M9 之后）

- 控制台会话 token 落库：签发的 Bearer Token 此前只存在进程内存里，服务重启即全员掉登录。`[schema]` Flyway `V11__auth_token.sql` 新增 `t_kb_auth_token`，只存 token 的 SHA-256 摘要（对齐 API Key 的处理），24h TTL 语义不变，改密仍然吊销该账号全部会话
- 评测 NDCG 超过 1：重叠切分让同一证据 span 命中多个候选时，IDCG 仍按声明的证据条数归一，实测出现 `NDCG=2.948`。理想相关数改取 `max(声明数, 观测数)` 并截断到 K
- 真流式从未生效：chat-preview 与对外 `/knowledge/chat` 把 `SseEmitter` 藏在 `ResponseEntity<?>` 后返回，Spring 按声明类型选返回值处理器，emitter 被交给消息转换器抛 `HttpMessageNotWritableException`。改为 `produces=text/event-stream` 的独立流式方法，`stream=true` 与 `Accept` 错配返回可操作的 400。同期新增 `CHAT_GENERATE_TIMEOUT_MS`（默认 60s）——生成此前与路由/改写共用 3s 读超时，真实生成必超时
- SSE 端点上抛出的业务异常被内容协商吃成裸 500：`Accept` 仅为 `text/event-stream` 时 JSON 错误信封无法协商渲染，过期 token 的 401 语义被掩盖。改为对 stream-only 的 `Accept` 手写 JSON 信封绕过协商

### 新增（M9）

- `[schema]` Flyway `V10__chunk_parent_offset.sql`：`t_kb_chunk` 增 `parent_start_offset` / `parent_end_offset`，语义为子片文本在父片正文中的 `[起, 止)` 字符偏移
- 父片精确剔除：偏移由切分器在切出子片时顺带落值，并做「按偏移截取父片必须等于子片原文」的一致性校验，不一致落 null。检索返回父片前按偏移倒序剔除被禁用子片的文本段，替换为固定标记「（已省略被禁用内容）」，`metadata.redacted_child_count` 记条数；任一禁用子片偏移为 null 则整片回退返回，不做半剔除
- 偏移失效收敛在标注写路径单点：子片编辑 / 合并 / 拆分置 null，父片编辑则清空其全部子片偏移
- 标注跨版本相似度辅助迁移：`AnnotationMigrationAdvisor` 用字符 3-gram 的**对称** Dice 系数（`2×|交| / (|A|+|B|)`）在同文档当前激活版本内取 top3、分数 ≥ `ANNOTATION_MIGRATION_MIN_SCORE`（默认 0.35），短文本不给候选；`pending-review` 响应增 `suggestions`（懒计算不落库），`POST /api/v1/annotations/{annotationId}/migrate` 逐条人工确认迁移，幂等、无批量端点、不自动迁移
- 图片 query：对外 `search` / `chat` 与管理端 chat-preview 入参增可选 `images`（**仅 base64，不收 URL** —— 外部 URL 是 SSRF 面），上限 3 张 / 单张 5MB / 总量 10MB。逐张走 VisionProvider 转文本后以 `[图片内容] ` 前缀拼到 query 尾部，拼接发生在 Query 改写**之前**；失败或无视觉模型时忽略全部图片继续纯文本检索并标 `image_understanding_unavailable`；纯图片无文本且理解失败返回 `INVALID_PARAM`

### 新增（M8）

- `[schema]` Flyway `V9__source_mapping.sql`：新增 `t_kb_source_mapping`（映射档案，`name` UK、`source_type` ∈ csv/xlsx/txt/html、`profile_yaml` 全文、`is_builtin`）。需求文档原称该表「一期已就位」系失实，实际从未建表，故本期为建表而非补列
- 聊天记录新增 TXT / HTML 两种导入格式：行首正则命名捕获组（TXT）与 DOM 选择器（HTML）随映射档案承载可自定义，内置留痕 / 微信 PC 模板；不匹配行占比 > 30% 直接报可操作错误，避免拿错格式静默出垃圾
- 字段映射档案维护：`GET|POST /api/v1/source-mappings`、`PUT|DELETE /api/v1/source-mappings/{mappingId}`、`POST /{mappingId}/copy`；内置模板启动时从 parser 侧 yml 幂等种子化入库，`is_builtin` 行不可删只可复制；导入时 `mapping_profile` 参数兼容旧的内置名
- parser 调用改为随请求携带 `profile_yaml` 全文，parser 不再只认本地文件（本地 yml 退为种子与默认值）
- 聊天聚合重叠滑窗：新增 `window_overlap`（默认 0 完全兼容顺切，约束 `overlap×2 < max_messages`），chunk metadata 增 `window_seq` 与 `msg_span`（会话内消息序号闭区间）
- 检索侧近重复窗口归并：库内融合后、重排前，同 `session_id` 且 `msg_span` 重叠率 ≥ 0.5 的命中只留排名最高者，被并者进 `metadata.merged_window_chunk_ids`（上限 5）。放在重排之前是为了不让交叉编码器为同一段内容付两次钱；非聊天 chunk 无 `msg_span`，零影响
- 修复既有缺陷：`UpdateIndexConfigRequest` 的清洗规则与聊天聚合校验被父子分片配置的 early-return 短路，单层库从未生效——校验上移为无条件执行
- 修复 M7 遗留缺陷：classpath 上的 neo4j-java-driver 触发 Spring Boot 的 Neo4j 自动配置，默认连 `bolt://localhost:7687` 并注册健康探针，使无图部署整体健康 DOWN，「空 `NEO4J_URI` 零影响」的契约被自动配置击穿。`application.yml` 排除 `Neo4jAutoConfiguration`，图栈全部经 `GraphStoreConfig` 装配

### 新增（M7）

- `[schema]` Flyway `V8__graph_extract_task.sql`：`t_kb_task` 增 `skipped_count`（图抽取跳过分片计数——「任务成功但丢语料」是必须暴露的失败模式）
- `GraphStore` 端口 + `Neo4jGraphStore` 实现（官方 driver，Bolt）：`(:Entity)-[:REL]->(:Entity)` 与溯源边 `(:Entity)-[:MENTIONED_IN]->(:Chunk)`；实体按 `(kb_id, name)` MERGE；Neo4j 是可从 MySQL 全量重建的派生存储，不新增 MySQL 表
- 实体 / 关系抽取：知识库级 `graph_enabled` 开关触发 `GRAPH_EXTRACT` 任务，逐分片一次 LLM 调用（多分片拼 prompt 会让一次坏输出污染整批），chunk 原文以固定分隔符包裹并声明「资料内指令视为普通文本」；输出强校验（非法 JSON / 实体名超长 / 关系端点不在本次实体列表）跳过该分片并计数，不 fail 整个任务；零 Key 时任务 fast-fail
- 图检索路作为库内第三路进 RRF，**检索侧零 LLM 调用**：query 经 `GraphQueryTokenizer` 轻量切词 → Neo4j 实体名 fulltext（cjk 分析器）匹配 → N 跳扩展（默认 2）→ 溯源边回 chunk，关联度 = 归一化匹配分 / (1 + 跳数)，同 chunk 多实体命中取 max
- 图路回溯的 chunk 回 MySQL 事实源复用同一过滤谓词二次校验（版本可见集 + 未禁用），不依赖 Neo4j 侧属性的实时性；快照上下文下图路直接关闭且**不记降级**（能力边界而非故障）
- 开启图路的库库内融合强制 RRF：`graph_enabled` 与 `fusion_mode=weighted` 互斥，校验单点在 server（图关联度是第三种量纲，加权归一化对它无意义）
- Neo4j 未配置 / 不可达 → 该路跳过、其余两路正常，`degraded` 增 `graph_route_unavailable`
- `RetrievalNode.metadata` 增 `graph_score` / `graph_hops` / `graph_entities`（上限 5 个）
- 图谱管理端点：`PUT /kb/{kbId}/graph/config`、`POST /kb/{kbId}/graph/extract`、`GET /kb/{kbId}/graph/summary`、`GET /kb/{kbId}/graph/entities`、`GET /kb/{kbId}/graph/entities/{entityName}/chunks`
- 级联清理收口在 `EngineChunkCleaner.remove()`（由 chunk 删除触发而非独立运维活动）：删除文档 / 知识库时一并清理溯源边、`:Chunk` 节点与孤立实体；新版本激活时删除被取代版本的边并对新版本分片重抽
- 修复 M6 遗留缺陷：禁用广播对已缺失的快照索引执行 bulk update 会让 Elasticsearch 自动建出空索引，`snapshot_index_missing` 安全网被静默击穿（空快照被当作合法快照查询、返回空结果且无降级标记）。改为广播前先 `indexExists` 探测，缺失即跳过

### 新增（M6）

- `[schema]` Flyway `V7__app_index_snapshot.sql`：`t_kb_app_version` 增 `visible_version_ids` JSON（按库分组的 document_version 集合）与 `index_snapshots` JSON（`[{kb_id, engine, physical_index_name}]`）
- 快照原语进端口：`FulltextStore` / `VectorStore` 各增 `snapshotIndex`、`dropIndex`、`indexExists`。Elasticsearch 走 `_clone`（段级硬链接，毫秒级；源索引写锁在 finally 必解——快照失败只赔发布不冻结知识库），Qdrant 走 scroll 游标分页拷贝（避 offset 窗口截尾）
- 快照物理索引命名 `kb_{kbId}_{嵌入段}_s{seq}`，`seq` 为库级自增序列；快照**不挂别名**、按物理名直查，实时索引与别名完全不动
- 发布流程扩展（八状态机不变）：门禁裁决之后、`RELEASED` 生效之前，同时冻结物理索引与版本可见集。只冻结索引不冻结可见集正是「回滚后召回全空」缺陷的根源。任一库快照失败 → 发布中止、版本停留原状态可重试、本次已建的快照索引回滚删除
- 检索调用上下文三分支收敛在 `RetrievalIndexContextResolver` 一处：经 `RELEASED` 版本调用取快照索引 + 固化可见集；`TESTING` 灰度 / chat-preview / 管理台调试 / 评测取实时别名 + 当前激活集合；M6 之前发布的旧 `RELEASED` 无快照数据则回退实时且**不记降级**（历史数据形态，不是故障）
- 快照索引不存在（如被误删）→ 回退实时别名，`degraded` 增 `snapshot_index_missing`
- 快照路径关闭孤儿自愈：快照召回的 chunk 若 MySQL 行已不存在，只丢弃出排序并记 info，**绝不触发引擎删除**——按实时语义自愈会跨索引误伤（正确性红线）
- 禁用广播：分片启停是全局质量止血，除实时别名外同步广播到该库全部生效的快照索引；内容性操作（编辑重嵌入 / 合并 / 拆分 / 删除）不碰快照
- 归档保护落地：`AppVersionPinChecker` 替换空实现，被任意未删除应用版本（含 `SUPERSEDED`）引用的 document_version 即 pinned，`VersionRetentionService` 跳过；`DocumentVersionResponse` 增 `pinned` / `pinned_by`
- 快照保留清理（`@Scheduled`，cron 默认 04:15）：每应用保留最近 `APP_SNAPSHOT_RETAIN_COUNT`（默认 3）个 `SUPERSEDED` 版本的快照，更旧的按「删物理索引 → registry 置待清理 → 清空两列」顺序清理（先清列会开出「pin 已解、快照仍在」的窗口）；`RELEASED` 的快照永不清理
- 版本可见集按库 Caffeine 缓存 + 激活切换时失效（10 万分片压测的前置条件），快照路径不走缓存

### 新增（M5）

- 应用配置由单库改为 `kb_refs: [{kb_id, weight}]`（1..15 个库，权重正整数），读侧兼容旧快照的单 `kb_id` 字段，兼容读收敛在 `AppConfigSnapshot.getKbRefs()` 一处；新增 `routing: {enabled, prompt}`
- 知识库路由（`RoutingService`）：应用挂 ≥2 库且开关打开时，ChatProvider 一次调用给出候选库，**输出与候选白名单求交集**（Prompt 注入防线）；解析失败 / 超时 / 交集为空 → 检索全部关联库并标 `route_fallback_all`；未配置对话模型时自动跳过（等同关闭，不记降级），单库应用不调用路由；Caffeine 缓存 key 含 query + 候选集 + 生效 prompt，失败不入缓存
- 跨库检索编排复用单库链路（不复制逻辑）：每库独立跑「多路召回 + 库内融合」产出库内排名，跨库按**名次**做 RRF（不用分数，跨库分数不可比）
- rerank 候选配额：候选上限是**全局总量**按 `kb_refs` 权重比例切分，向下取整、余量归权重最高的库，只在实际出候选的库间分配（空库不占预算）
- `applied` 增 `routed_kb_ids`，`nodes[].metadata` 增 `kb_id`（管理端单库调试也一并填，避免同一元数据两条路径不一致）；chat 响应的 `routed_kb_ids` 在顶层
- 多库时 `applied.fusion_mode` 如实返回 `rrf`（最终排序确由跨库 RRF 产生），不谎报配置值
- 多库时库级单值默认（检索参数、改写 / 重排开关）取声明的第一个库
- 新增配置键 `RETRIEVAL_MAX_LINKED_KB`（15）、`RETRIEVAL_ROUTING_CACHE_TTL_MINUTES`、`RETRIEVAL_ROUTING_CACHE_MAX_SIZE`

### 新增（M4c）

- `[schema]` Flyway `V6__app_release_and_open_api.sql`：新增 `t_kb_app`、`t_kb_app_version`（八状态机 + `released_slot` 生成列唯一索引保证「单应用至多一个 RELEASED」）、`t_kb_api_key`、`t_kb_api_audit_log`
- 应用与版本管理：`/api/v1/apps` CRUD、建版本、`submit-test`、`release`、`rollback`；八状态（DRAFT / TESTING / GATING / GATE_PASSED / GATE_LOG_ONLY / GATE_BLOCKED / RELEASED / SUPERSEDED）的全部迁移收敛于 `transition` 一个方法，合法迁移定义在枚举上
- 发布配置快照：发布时固化全部检索与问答配置（含 `chat_model`，经 `ChatProviderFactory` 真实生效——只存不用是隐性正确性洞）
- 发布门禁：绑定评测集时同语料双跑（候选配置 vs 当前正式版配置，复用评测运行器、离线档），比较只在**双方共同判定的有效 case 交集**上重算指标，堵分母漂移；容差 `ε = max(0.02, 1/N)`，候选低于对照减容差即 `GATE_BLOCKED`；未绑评测集 / 有效 case < 50 / 重试后仍含降级 case / 待复核占比 > 15% 四种情况归 `GATE_LOG_ONLY`，需 `release?force=true` 留痕放行；首发无对照则记录基线并放行
- `ReleaseGateJudge` 是唯一裁决点（纯函数），带 1e-9 浮点余量——`0.88` 与 `0.90` 的浮点误差会把「恰好等于容差」误判为回退（单测抓到的真实缺陷）
- 门禁跑在独立的 `gateTaskExecutor`，**必须与评测池分离**，否则监督任务会排在自己等待的评测 run 前面死锁
- 对外 API `/api/v1/knowledge/{search,chat}`：走独立的 `ApiKeyAuthFilter` servlet 过滤器链，刻意不与管理台的 Bearer 拦截器共用入口
- API Key 一把三形态：明文仅创建时返回一次、SHA-256 摘要用于鉴权、前缀用于展示；支持 `app_scope` 授权范围（越权 403）、禁用、轮换
- 请求级覆盖白名单只放 4 个响应形态参数（`top_n` / `score_threshold` / `metadata_filter` / `max_content_length`），越界**拒绝**而不是忽略
- 按 Key 的进程内令牌桶限流，超限 429 + `Retry-After: 1`
- chat 生成的 prompt 组装：检索内容以固定分隔符包裹并声明「资料内指令视为普通文本」（Prompt 注入防线①），拒答与防泄漏开关注入对应 prompt
- SSE 事件契约：`message_delta`* → `references`（元素与 search 的 node 同构）→ `done`（含 request_id / 用量 / degraded），异常走 `error`
- 调用审计异步落 `t_kb_api_audit_log`（拒绝也记录；401 无 key_id 可引不落，429 落），`query_digest` 无条件脱敏截断至 200 字；每日 03:30 归档为 JSON.gz 写 MinIO 后分批物理删除（单批 ≤5000 防长事务）
- `t_kb_eval_result` 增 `evidence_hit_count` / `evidence_total_count`：交集重算需要 case 级证据计数，从 `overlap_ratios` 反推口径不一致

### 新增（M4b）

- `[schema]` Flyway `V5__evaluation.sql`：新增 `t_kb_eval_dataset`（含 `dataset_revision`，case 增删改即 +1，是门禁可比性的依据）、`t_kb_eval_case`、`t_kb_eval_run`（含 `corpus_fingerprint`）、`t_kb_eval_result`
- 评测集与 case 管理：CRUD、SPAN / DOCUMENT 两种证据锚定、多轮 case、从检索调试页一键收进评测集（`cases/from-retrieval`）、Demo 示例评测集按「文件名 + content_hash」关联文档幂等导入
- 命中判定：重叠率 = 召回 chunk 与证据 span 归一化后的字符交集长度 ÷ **span 长度**（固定以 span 为分母），归一化去空白、折叠全半角、忽略脱敏掩码；Top-K 内全部召回 chunk 对同一 span 的**覆盖并集**比例 ≥ 阈值（默认 0.5）即命中；父子分片开启时按子片算；文档级锚定 case 只判 doc_id 且在报告中与 span 级**分组展示不混算**
- 指标：Recall@K / Precision@K / Hit Rate / MRR / NDCG@K，比例类指标输出 95% Wilson 置信区间。**置信区间只作展示、不参与任何判定**——门禁的噪声控制由容差负责，两套机制不叠加
- 报告分组：全体 / span 级 / 文档级 / 单轮 / 多轮
- 配置矩阵：一次提交 1..6 组配置产生 N 个 run，共享 `dataset_revision` 与 `corpus_fingerprint` 以便横向对比；`mode` ∈ BM25_ONLY / VECTOR_ONLY / HYBRID / HYBRID_RERANK。为此 `RetrievalCommand` 增 `bm25RouteEnabled` / `vectorRouteEnabled` 强制关路能力，否则配了 Key 之后 BM25_ONLY 会退化成 HYBRID，四配置对比失去意义
- 零 Key 环境下向量类 mode 直接置 `FAILED` 并写明原因，不产生误导性指标
- 离线执行档 `OfflineExecutionContext`（ThreadLocal）：改写与重排超时统一放宽到 `EVAL_OFFLINE_TIMEOUT_MS`（默认 10s），降级不计入生产监控窗口；降级 case 自动重试（默认 2 次），重试后仍降级则 run 仍标 SUCCESS 但 `case_degraded > 0` 并在报告顶部提示
- 费用护栏：`runs/estimate` 提交前返回嵌入 / 重排 / 改写 / judge 各自的预估调用次数
- LLM-as-judge：正确性 / 引用忠实度 / 完整性各 1-5 分，固定英文 prompt 并版本化，`temperature=0`，judge 模型可独立配置；只有相同 judge 配置的 run 之间允许比分，judge 分**不参与门禁**
- LLM 语义切分策略（`LLM_SEMANTIC`）：prompt 只要求返回切割点，原文由代码按位置切、内容零改写；切割点非法时该窗口降级为按长度切分并记 error；切分结果按 `content_hash + 模型 + 提示词版本` 缓存到 MinIO，与 `parsed.json` 同一存储层、随文档版本天然清理
- 填实 M4a 的两个占位：`activate-impact` 的 `affected_eval_case_count` 改为真实统计；版本激活切换时同步扫描锚定该文档的 span 级 case，证据在新激活版本中匹配不上的置 `EVIDENCE_STALE`
- `run` 与 `compare` 端点：不同 `dataset_revision` 或不同 judge 配置的 run 返回 `comparable=false` 并给出原因
- 修复：`ChatMessage` 只有 final 字段与 `@AllArgsConstructor`，能写不能读——M2 造它时只用于序列化，评测第一次读回多轮 case 即 run 失败。已加 `@JsonCreator`
- 修复：`split_strategy` 原样存库不校验，既绕过「零 Key 不可选 LLM_SEMANTIC」的校验，又会在切分路由处静默失效。已在 service 单点归一化 + 非法值 `INVALID_PARAM`

### 新增（M4a）

- `[schema]` Flyway `V4__annotation.sql`：新增 `t_kb_annotation`（幂等键 + `chunk_text_hash` + `inherit_status`）
- 文档级版本管理：同名文件二次上传按 `content_hash` 与三项指纹判定 —— hash 变则 major+1 且 minor 归零，hash 不变而 parse/chunk/embedding 任一指纹变则 minor+1，全同则不建新版本并在响应标 `duplicated=true`
- 新版本构建期间旧激活版本继续服务，构建成功后原子切换，原激活版本退回 `READY`（支持秒级回滚）而非 `ARCHIVED`
- 指纹复用：`content_hash` + 解析指纹相同则复用 MinIO 中的 `parsed.json` 不重调 parser；切分指纹也相同则直接复制上一版 chunk 行（新 ID、重写父链）。**向量仍会重算**——两个引擎端口都是只写投影、读不回向量，MySQL 也不存向量；零 Key 下则完全零成本
- 版本管理 API：版本列表、激活切换、`activate-impact` 切换前影响预检；`rollback_mode` 判定 —— 目标为 `READY` 且分片仍在走 `INSTANT` 同步切换，目标为 `ARCHIVED` 走 `REBUILD` 从解析产物重建
- 保留策略：非激活版本按创建时间倒序保留 `DOC_VERSION_RETAIN_COUNT`（默认 3）个 `READY`，超出的置 `ARCHIVED` 并清理其 chunk 行、引擎文档与同步记录，**保留 MinIO 原件与 `parsed.json`** 作为 REBUILD 的依据
- 归档保护接口 `VersionPinChecker` 就位（本期为恒返回空集的默认实现，M6 接入真实快照引用）
- 内容哈希去重提示：上传时若同库其他文档已有相同 `content_hash`，响应给 `duplicate_of_doc_id`，仅提示不共享物理分片
- 分片标注四种操作，统一走「MySQL 事实源先行 → 重嵌入 → 双引擎同步」：编辑正文（重嵌入）、启停（不重嵌入，正文未变）、合并（同文档同版本、seq 连续、同 parent）、拆分（字符偏移升序且落在正文内）
- 父子分片下的禁用语义：禁用子片不参与召回，父片因其他子片命中而返回时以 `metadata.disabled_child_ids` 标注；KB 级开关 `hide_parent_with_disabled_child`（默认 false）打开后含禁用子片的父片整体不返回；禁用与启用父片都级联子片（只降不升会让重新启用的父片永久不可召回）
- 标注与版本的关系：标注绑定 `document_version_id`，新版本不自动继承；**禁用类标注按 `chunk_text_hash` 完全相同自动继承**（开关 `inherit_disable_annotation` 默认 true，精确匹配不做相似度）；其余标注进 `annotations/pending-review` 清单
- 修复真实隐患：`loadChunks` 原本在 SQL 里过滤 `enabled=1`，于是「行被禁用」与「行不存在」在引擎命中侧完全同形，孤儿自愈会把合法的禁用分片从两个引擎里删掉，重新启用后将永久召回不到。现已分离——缺失行照旧自愈删除，禁用行只从排序中剔除并记 info

### 新增（M3）

- `[schema]` Flyway `V3__image_asset_and_chat_source.sql`：新增 `t_kb_image_asset`（另建唯一键 `(document_version_id, source_image_id)`——parser 返回的 `img_1` 是文档内编号，不能做全局唯一键）；`t_kb_document` 增 `source_key`（聊天会话的逻辑文档标识，要扛住改名与二次导出）
- `VisionProvider` 落地：DashScope 兼容端点，模型默认 `qwen-vl-max`，超时 20s（图片理解慢于文本）；`model-status` 增视觉模型状态
- 图片资产管线（解析之后、切分之前）：图片入 MinIO 并登记资产行 → 逐图调 VLM 生成文本代理（描述 + 转录）→ **代理插回占位符原位**参与统一切分；chunk 的 `metadata.image_urls` 记对应 object key，检索返回时转限时预签名 URL
- 独立上传的图片文件单独成片，`chunk_type=image`；VLM 未配置或调用失败时该图跳过、文档其余部分正常入库，资产行置 `SKIPPED` / `FAILED` 供后续补跑
- 扫描件支持：parser 把无文本层的整页渲染成 PNG 交给 server 走 VLM 识别（**不引入本地 OCR**——为一个兜底路径引入数百 MB 依赖与 ARM 构建风险不划算；本地 OCR 兜底见 M8）
- 清洗规则（KB 级）：去页眉页脚（跨页重复行检测）→ 去水印 → 正则替换 → 脱敏，执行顺序固定、每步独立开关；脱敏覆盖手机号 / 身份证 / 银行卡 / 邮箱，聊天记录导入时默认开启
- 解析预览与确认：`parse_preview_required` 开关（默认 false 保证批量上传顺畅），开启后管线在清洗完成后暂停于 `PENDING_CONFIRM`，提供预览、按当前规则重解析、单个与批量确认
- 聊天记录两步式导入：`chat-imports` 返回会话匹配预览（不落库）→ `confirm` 执行；逻辑文档标识 = 来源渠道 + `session_id`，已存在则建新版本、新会话则建新文档，一个文件多会话拆多文档；按窗口无重叠顺切，chunk 的 `chunk_type=chat_log` 且 metadata 写 `session_id` / `session_name` / `sender` / `msg_time`
- 告警 Webhook：任务连续失败 / 检索降级率 / 双写积压三类触发，消息体兼容钉钉 / 企微 / Slack 的通用 text 结构，同类型告警有静默期（默认 30 分钟）；未配置 URL 时降级为 error 日志
- Demo 一键导入：`system/demo/import` 建「Demo 知识库」并导入随 deploy 仓分发的文档集，幂等；`system/demo/status` 供管理台判断按钮可用性
- 解析产物由 `parsed.md` 改为 `parsed.json`（markdown + pages + warnings）：页眉页脚检测需要分页文本，重建时不能重调 parser；读取端对旧 `.md` 键回退，M1/M2 版本仍可重建
- `current_config_fingerprint` 由单一切分指纹改为解析 + 切分组合指纹，否则改清洗规则无法标记 `config_stale`。**副作用**：升级后 M1/M2 时期的文档会显示 `config_stale=1`，重建后归零
- 修复：provider 的 401 被误报为网络不可达。传输层用 `SimpleClientHttpRequestFactory`（JDK HttpURLConnection），服务端 401 带 `WWW-Authenticate` 时无法重放请求体而抛 I/O 异常，真正的状态码到不了错误分类器，所有凭证问题都落进「网络不可达」桶，把排查方向指向网络。改用 `JdkClientHttpRequestFactory`
- 修复：银行卡脱敏漏 17/18/19 位。正则只匹配 16 位与 20 位，而 ISO/IEC 7812 是 16-19 位（含多数银联卡），开关显示已启用却放行
- 修复：Elasticsearch 别名只追加不切换。`putAlias` 从不摘旧索引也不指定写索引，嵌入版本段一变（换嵌入模型，或丢 Key 退回 `none`）第二个物理索引加入同一别名，ES 无法判定写入目标，该库**所有写入与删除永久失败**。改为 `updateAliases` 单次原子操作：摘除其余索引 + 以 `is_write_index` 加入目标

### 新增（M2）

- `[schema]` Flyway `V2__ik_dict_and_retrieval_config.sql`：新增 `t_kb_ik_dict`（词条 UK，EXT/STOP，可停用），`t_kb_knowledge_base` 增 `retrieval_config` JSON 列
- Query 改写：DashScope OpenAI 兼容 `chat/completions` 落地 `ChatProvider`；800ms 硬超时降级、Caffeine 缓存（key 含多轮会话）、多轮指代消解；改写结果只当检索词用，单行化 + 长度截断作为 Prompt 注入防护；超时与失败分别标注 `query_rewrite_timeout` / `query_rewrite_error`
- 重排：DashScope 原生 `text-rerank` 端点落地 `RerankProvider`；候选 ≤50、1.5s 硬超时，超时与失败分别标注 `rerank_timeout` / `rerank_error`
- 融合升级：新增 `weighted` 模式（每路候选集内 min-max 归一化，`w_vec` 可调，BM25 权重取补），`rrf_k` 可配；`FusionStrategy` + `FusionRouter` 组合替代分支
- 阈值语义定型：只作用于跨查询可比的分数（重排分 > 归一化 cosine），BM25 单路时失效并返回 `threshold_inactive`；`score_type` 扩展 `rerank | fused_rrf | fused_weighted`
- 父子分片：两级切分复用既有定长策略，引擎只索引子片、父片正文只存 MySQL；检索后按 `parent_id` 归并（max 聚合），候选按「归并后父片数达标或子片数达上限」换算
- search API 扩展：`score_threshold`、`fusion{mode,w_vec,rrf_k}`、`rerank_enabled`、`rewrite_enabled`、`messages`、`metadata_filter`；响应增 `applied` 信息条与各路原始分 / 归一化分 / 融合分 / 重排分
- `metadata_filter` 引擎侧下推：Elasticsearch bool filter 与 Qdrant 结构化 filter 双实现；索引管线把 `chunk.metadata` 的固定键写入引擎字段
- 双写补偿：`@Scheduled` 扫 `t_kb_chunk_index_sync`，按物理索引分组重推，重试上限后放弃并打错误日志；文档 / 知识库删除同步清理引擎；检索命中但事实源缺失时异步自愈删除
- 配置变更与重建：`PUT /api/v1/kb/{kbId}/index-config` 重算指纹并刷新 `config_stale`，`POST /api/v1/kb/{kbId}/rebuild` 在同一物理索引内先写新片再删旧片
- ik 词典：管理 CRUD + 启停 API，`/internal/dict/ik/{ext|stop}.txt` 免登录热更新通道（`Last-Modified` / `ETag`，未变更返回 304）
- `GET /api/v1/system/model-status` 增加重排与对话模型的配置状态
- 端口调整：应用默认端口 8080 → 20000，parser 默认地址 → `http://127.0.0.1:20001`

### 新增（M1）

- `[schema]` Flyway `V1__baseline.sql`：建立 10 张基线表（知识库、文档、文档版本、分片、分片×物理索引同步状态、索引注册表、异步任务、管理员、系统设置、登录审计）
- 启动初始化：数据库无管理员时创建 admin 账号，随机密码打印一次并强制首登改密
- 登录鉴权：BCrypt 校验 + Bearer Token（默认 24h）+ 基于登录审计表的防爆破锁定（默认 5 次锁 15 分钟）
- 知识库 CRUD，创建时同步建物理索引、绑定别名并登记 `t_kb_index_registry`
- 文档上传：扩展名 + 文件头（magic number）+ 大小三重校验，原件存 MinIO 私有桶
- 异步索引管线：调用 parser 解析 → 按长度切分（默认 600 token / 重叠 100）→ 分片落库（含 `chunk_text_hash`）→ 写全文索引 →（有 Key 时）嵌入并写向量索引 → 按物理索引登记同步状态 → 版本激活
- 检索 API：有 Key 走向量 + BM25 双路 RRF（k=60）融合，零 Key 走 BM25 单路并返回 `degraded=[vector_route_unavailable]`；两路均在引擎侧强制过滤激活版本与启用状态
- Provider 抽象：嵌入（DashScope，OpenAI 兼容 HTTP 端点）落地，重排 / 对话 / 视觉三类接口与未配置占位实现就位
- 引擎抽象：`VectorStore` 双实现（Elasticsearch dense_vector 与 Qdrant），`FulltextStore` Elasticsearch 实现；向量分统一换算为标准 cosine 后线性映射到 `[0,1]`
- `GET /api/v1/system/model-status`：向管理台透出是否配置嵌入模型与当前向量引擎
- `GET /actuator/health`：含 MySQL、Elasticsearch、MinIO 探活，配置 Qdrant 时增加 Qdrant 探活
- 统一响应包装、错误码枚举与 `request_id` 全链路透传（入口 filter 生成，写入 MDC 并透传至 parser）
