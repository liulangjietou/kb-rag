# M14 开发契约（三期第一批：连接器 / 元数据抽取 / 切分扩展 / 混合重排 / 多模态索引 / 以图搜图 · 增量于 M1-M13 契约）

> 需求依据：知识库需求文档 §13 后续规划 + 竞品（阿里云百炼知识库）能力对齐（文档与图片口径，音视频与计费明确排除）。本期六个特性在单分支 `feature/m14` 一次交付。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；web 枚举展示走 metaOf；单测离线可跑（`mvn -B -ntp verify` / `pytest`）。
> 用户已确认的三项选型：①连接器 SPI + 首个实现 **S3/OSS 兼容对象存储**（MinIO 可本地自测）；②多模态向量 **DashScope multimodal-embedding-v1**（与全家桶同 Key）；③单里程碑单契约。

## 0. 范围与边界

**本期做（六特性）**：

| # | 特性 | 一句话定义 |
|---|---|---|
| F1 | 外部数据源连接器 | 连接器 SPI + S3/OSS 实现：登记 bucket/prefix → 扫描对象 → 走既有上传链路入库，ETag 增量同步 |
| F2 | 配置化元数据抽取 | KB 级 metadata_rules（常量/正则/词表三类），切分后逐 chunk 抽取入 metadata 并镜像引擎，检索侧 custom 等值过滤 |
| F3 | 切分策略扩展 | 新增 `separator`（分隔符/正则）、`heading`（markdown 标题层级）、`page`（解析页边界）三个策略，并同步进 `SplitStrategy` 枚举——枚举是配置写入的唯一白名单，漏登记的策略码会被 `KnowledgeBaseService` 拒成 INVALID_PARAM，实现再全也是死代码 |
| F4 | Rerank 混合模式 | `rerank_mode=hybrid`：语义重排分与归一化 BM25 分线性加权决定排序 |
| F5 | 视觉理解整页索引 | KB 级 multimodal 开关：IMAGE chunk（含扫描页整页渲染）额外产多模态向量，检索新增第三召回路 |
| F6 | 以图搜图入口 | 管理台检索调试页支持图片查询；multimodal 开启时图片直接嵌入多模态空间检索，否则回落 VLM 转写 |

**本期不做**：Confluence/飞书/钉钉等 API 型连接器（SPI 已就位，留后续里程碑）；百炼"变量"型元数据（无请求级上下文来源）；rerank 模型端原生 hybrid（qwen3-rerank hybrid 是模型内能力，本期以线性混合实现同等语义）；音视频、计费、RBAC（D17 延后决策不变）。

**兼容红线**：纯新增表/端点/配置键，存量行为零变化；所有新 JSON 配置字段缺省即现状（`metadata_rules` 空、`split_strategy` 旧值不变、`rerank_mode=semantic`、`multimodal_enabled=false`）；旧配置行反序列化靠 `@JsonIgnoreProperties(ignoreUnknown=true)` 天然兼容。

**开发顺序**（依赖关系决定）：F1 → F2 → F3 → F4 → F5 → F6（F6 依赖 F5 的 provider 与路由）。

## 1. 数据模型（Flyway V15，两张新表；其余全部落既有 JSON 配置列）

| 表 | 定义 |
|---|---|
| t_kb_ext_source | `id` BIGINT PK AUTO、`source_id` VARCHAR(64) UK（`exts_` 前缀）、`kb_id` VARCHAR(64) NOT NULL、`source_type` VARCHAR(16) NOT NULL（本期仅 `s3`）、`name` VARCHAR(128) NOT NULL、`endpoint` VARCHAR(512) NOT NULL、`region` VARCHAR(64) NULL、`bucket` VARCHAR(128) NOT NULL、`prefix` VARCHAR(512) NULL、`access_key` VARCHAR(256) NOT NULL、`secret_key` VARCHAR(512) NOT NULL、`sync_enabled` TINYINT NOT NULL DEFAULT 1、`last_sync_at` DATETIME NULL、`last_sync_status` VARCHAR(16) NULL（SUCCESS/PARTIAL/FAILED）、`last_error` VARCHAR(512) NULL、通用列；KEY `idx_kb(kb_id)`，UK `uk_kb_name(kb_id, name)` |
| t_kb_ext_source_item | `id` BIGINT PK AUTO、`source_id` VARCHAR(64) NOT NULL、`object_key` VARCHAR(1024) NOT NULL、`object_key_hash` CHAR(64) NOT NULL（sha256，等值定位）、`etag` VARCHAR(128) NULL、`doc_id` VARCHAR(64) NULL、`last_status` VARCHAR(16) NULL（SUCCESS/UNCHANGED/SKIPPED/FAILED）、`last_error` VARCHAR(512) NULL、`last_sync_at` DATETIME NULL、通用列；UK `uk_source_object(source_id, object_key_hash)`，KEY `idx_source(source_id)` |

- **凭证存储取舍**：`secret_key` 明文落库、读 API 恒返回 `******`、更新时传空 = 保留旧值。前提与 D17 一致：管理台单管理员 + 网络隔离；引入 KMS/信封加密属权限体系批次。CHANGELOG 醒目声明。
- JSON 配置列扩展（无 DDL）：`t_kb_knowledge_base.index_config` 增 `metadata_rules`、`split_separator`、`split_separator_is_regex`、`split_heading_level`、`multimodal_enabled`；`retrieval_config` 增 `rerank_mode`、`rerank_w_semantic`。

## 2. F1 外部数据源连接器（S3/OSS）

### 2.1 SPI（kb-domain port，为后续 Confluence/飞书预留）
- `ExternalConnector`：`String type()`、`List<RemoteObject> listObjects(ExtSourceConfig config)`（RemoteObject = key/etag/size/lastModified）、`byte[] fetchObject(ExtSourceConfig config, String objectKey)`、`HealthStatus testConnection(ExtSourceConfig config)`。
- `ConnectorRouter`（kb-domain service，先例 SplitterRouter）：按 `source_type` 从容器收集实现路由；未知类型 INVALID_PARAM（不回落——连不上的连接器没有"默认行为"可言）。
- kb-infrastructure 实现 `S3CompatibleConnector`（type=`s3`）：**复用已依赖的 MinIO SDK**（对象存储适配器同款），listObjects 带 prefix、单次列举上限 `max-objects-per-source`（默认 500，超出记 PARTIAL + last_error 说明截断）；仅保留扩展名在 `kb.upload.allowed-extensions` 白名单内的对象。
- **endpoint 不过 UrlGuard**：连接器 endpoint 是管理员配置项而非终端用户输入，且合法场景就是内网 MinIO/OSS 内网域名——与 M12"用户给什么抓什么"的威胁模型不同。契约明示此取舍。

### 2.2 同步语义（登记后异步首同步、手动 sync、定时任务三处共用）
1. `testConnection` 失败 → 源级 FAILED + last_error，不逐对象；
2. listObjects → 逐对象比对 item 行：`etag` 未变 → UNCHANGED 不触上传链；绑定文档在回收站 → SKIPPED（M12 同口径文案）；对象已从 bucket 消失 → item 记 SKIPPED"对象已不存在"（**弱绑定：不动文档**）；
3. 其余 → `fetchObject` → `DocumentService.upload(kbId, fileName, bytes)`——派生文件名 `{对象名 slug}-{key_hash 前 8 位}.{ext}`（M12 先例，防不同 key 撞名合并）；回填 doc_id/etag；
4. 单对象失败记 item FAILED 不中断本源其余对象；源级状态 = 全成 SUCCESS / 有失败或截断 PARTIAL / 源级错误 FAILED；
5. 治理/版本/索引管道全部经上传链路免费继承，**不新增入库旁路**（M12 铁律沿用）。

### 2.3 端点（ExtSourceController + ExtSourceService，kb-app 包 extsource）
- `POST /api/v1/kb/{kbId}/ext-sources`：`{source_type, name, endpoint, region?, bucket, prefix?, access_key, secret_key, sync_enabled?=true}` → 落行 + **异步**触发首同步（对象数不可控，不能同步阻塞登记请求；与 M12"登记即抓"的差异点，契约明示）；同 KB 重名 INVALID_PARAM。
- `GET /api/v1/kb/{kbId}/ext-sources?page=&size=`：列表（secret 恒脱敏）。
- `POST /api/v1/ext-sources/{sourceId}/sync`：手动同步，**异步受理**返回 `{accepted: true}`，结果看列表状态。
- `GET /api/v1/ext-sources/{sourceId}/items?page=&size=`：对象明细（同步结果逐对象可见）。
- `PUT /api/v1/ext-sources/{sourceId}`：可改 name/endpoint/region/bucket/prefix/access_key/secret_key(空=保留)/sync_enabled。
- `POST /api/v1/ext-sources/{sourceId}/test`：连通性测试（同步执行，返回 HealthStatus）。
- `DELETE /api/v1/ext-sources/{sourceId}`：硬删源行 + items 行；**不动文档**。

### 2.4 配置键（KbProperties.ExtSource）
`kb.ext-source.sync-cron=0 0 3 * * *`（EXT_SOURCE_SYNC_CRON）、`sync-enabled=true`、`sync-batch-size=10`（每轮源数）、`max-objects-per-source=500`、`fetch-timeout-ms=30000`。定时任务与 M12 同模式：disabled 短路、单源失败不中断批次。

## 3. F2 配置化元数据抽取

### 3.1 规则模型（KbIndexConfig.metadata_rules，≤10 条）
```json
[{"key":"dept","type":"constant","value":"研发部"},
 {"key":"contract_no","type":"regex","pattern":"合同编号[:：]\\s*(\\S+)"},
 {"key":"product","type":"keyword_match","keywords":["百炼","通义","灵码"]}]
```
- `key` 规范 `^[a-z][a-z0-9_]{1,31}$`，且不得与 ChunkMetadataKeys 既有保留键冲突（冲突 INVALID_PARAM，Controller 单点校验）；
- `constant`：固定值（截 256）；`regex`：Java Pattern（长度 ≤64，编译失败 INVALID_PARAM），命中取第一个捕获组、无捕获组取整体匹配，值截 256，未命中不写键；`keyword_match`：词表（≤50 词、每词 ≤32 字符），值为命中子集 JSON 数组，空命中不写键。

### 3.2 执行与存储
- 执行点：索引管线切分之后、落 chunk 之前，`MetadataRuleExtractor`（kb-domain service，纯函数）逐 chunk 抽取，结果合并进 `metadataOf(...)`——与保留键冲突时抽取值让位（保留键语义优先）。
- 引擎镜像：抽取键统一加 **`ext_` 前缀**写入 ES 字段（dynamic template：`ext_*` → keyword）与 Qdrant payload；MySQL metadata JSON 存原始键名（`dept` 而非 `ext_dept`）——前缀只是引擎侧命名空间，防与既有 mapping 冲突。
- 指纹：`metadata_rules` 计入 chunk 指纹（改规则 → config_stale → 重建后生效，M3 先例）。

### 3.3 检索过滤
- `MetadataFilterRequest` 增 `custom: {"dept":"研发部"}`（Map<String,String>，AND 等值；keyword_match 键按"数组包含"匹配）；key 不符规范 INVALID_PARAM；映射到引擎 `ext_` 字段。`MetadataFilter` 域模型、开放 API `PublicSearchRequest.metadata_filter` 同步扩展（白名单参数本就含 metadata_filter，无新增覆盖面）。

## 4. F3 切分策略扩展（三个新 TextSplitter bean，SplitterRouter 自动收集）

| 策略 code | 配置 | 语义 |
|---|---|---|
| `separator` | `split_separator`（1-64 字符，默认 `\n\n`）、`split_separator_is_regex`（默认 false） | 按字面量/正则分隔符切段后贪心装包到 `chunk_max_tokens`（复用 FixedLengthTextSplitter 的装包语义）；正则编译失败 INVALID_PARAM（Controller 单点）；分隔符不保留 |
| `heading` | `split_heading_level`（1-6，默认 2） | 按 markdown `^#{1,level} ` 标题行开新段，标题行归属其后内容；全文无标题 → 整文回落 fixed_length |
| `page` | 无新参数 | 按解析产物 `pages[]` 边界切分（管线级特例：不走 markdown 正则，而是消费**逐页清洗后的页 markdown** + 页区间，见下）；无 pages 的格式（txt/html 单页）→ 整文一页 |

- **`page` 策略消费的是清洗后的正文（v1.1 修订）**：初版契约写的"从 parsed.json 取分页文本"已作废——`pages[].text` 是解析原文，既不含图片占位符行，也从未经过清洗四步，照此实现会让按页切分的知识库把未脱敏的 PII 直接写进索引、且每个分片的 `image_urls` 恒空。现行链路：parser 侧 `pages[].markdown` 返回该页对应的 markdown 切片（含 `## Page N` 标题与 `[[IMAGE:id]]` 占位符行，旧产物为 null 时回退 `text`）→ server 侧 `PagedContentAssembler` 逐页跑清洗四步（页眉页脚用**全文档**检出的行集，水印/正则/脱敏逐页应用）与占位符替换，再按 `\n\n` 拼回整篇并记录每页区间 `PageRange{page_no,start,end}` → `PageSplitter` 按区间切。**无清洗规则时逐页拼接结果与 parser 的 `markdown` 逐字符相等**，即按页切分与定长切分吃的是同一份正文；区间随预览产物落 `page_ranges`，确认入库时按存档区间切，不重算；
- **超长兜底统一**：三个策略产出的任何超过 `chunk_max_tokens` 的段落，回落 fixed_length 二次切分（LLM 语义切分已有同款先例）；
- **父子分片组合限制（v1.1 收窄）**：`parent_child_enabled=true` 时 `split_strategy` **仅允许 `fixed_length`**，其余一律 INVALID_PARAM（Controller 单点）。初版写的"允许 `fixed_length`/`llm_semantic`"是失实的：`ParentChildSplitter` 是把定长策略与自身组合两遍，并不按 `split_strategy` 取策略，配 `llm_semantic` 时实际跑的是定长，而分片指纹记的却是 `llm_semantic`——配置读起来是一回事、索引出来是另一回事。此处与 M4b-CONTRACTS.md §4「父子 + LLM_SEMANTIC 本期不支持」口径统一；页/标题边界与"父长子短两级预算"语义天然冲突，同样不做貌合神离的组合；
- 新策略均不产 title/summary/keywords 元数据（那是 LLM 策略的伴生物）；`page` 策略 chunk metadata 增 `page_no`（进 ChunkMetadataKeys 声明，镜像引擎可过滤）。

## 5. F4 Rerank 混合模式

- `KbRetrievalConfig` + `SearchRequest` 增：`rerank_mode`（`semantic`(默认)/`hybrid`）、`rerank_w_semantic`（0-1，默认 0.7）；五层优先级照旧（请求 > KB > 部署默认 `kb.retrieval.rerank-mode=semantic`）。
- **实现**：融合阶段为每个候选保留其 BM25 路原始分（`RetrievalCandidate` 增 nullable `bm25Score`——纯向量命中无此分）；rerank 成功后，`hybrid` 的排序分 = `w * rerankScore + (1-w) * minmax(bm25Score)`（min-max 在本次候选集内归一化，无 BM25 分按 0 计）。
- **阈值语义不变**：`score_threshold` 仍作用于纯语义 rerank 分（ScoreThresholdPolicy 不动）——min-max 归一化分跨查询不可比，把它混进阈值会摧毁"绝对阈值"承诺；hybrid 只影响**排序**。契约明示，前端参数面板同步说明文案。
- 降级链路照旧：rerank 未配置/超时/失败 → 保持融合序，`hybrid` 请求同样记 `rerank_unavailable`/`rerank_timeout`/`rerank_error`，不新增降级码。

## 6. F5 视觉理解整页索引（多模态向量）

### 6.1 Provider（新 port + DashScope 实现）
- kb-domain 新 port `MultimodalEmbeddingProvider`：`providerName()/model()/dimension()/isConfigured()/maxBatchSize()`、`List<float[]> embedTexts(List<String>)`、`List<float[]> embedImages(List<ImageInput>)`（ImageInput = bytes + mediaType）、`healthCheck()`。
- kb-infrastructure `DashScopeMultimodalEmbeddingProvider`：DashScope 原生端点 `POST {url}/services/embeddings/multimodal-embedding/multimodal-embedding`（**非 OpenAI 兼容面**，与 rerank 同款全 URL 配置），model `multimodal-embedding-v1`，1024 维；图片以 **data URL（base64）内联**（桶私有不出网，VisionProvider 同一理由）。⚠️ 若 provider 实测拒收 data URL，实现期降级为 MinIO 预签名 URL 并回补本契约修订记录。
- 配置键（KbProperties.MultimodalEmbedding）：`kb.multimodal-embedding.model=multimodal-embedding-v1`（MULTIMODAL_EMBEDDING_MODEL）、`api-key`（默认继承 DASHSCOPE_API_KEY，**blank = 整个多模态能力关闭**）、`url=https://dashscope.aliyuncs.com/api/v1`、`dimension=1024`、`batch-size=8`、`timeout-ms=30000`。零 Key 时 `NoopMultimodalEmbeddingProvider`（isConfigured=false）。

### 6.2 索引侧
- `KbIndexConfig.multimodal_enabled`（默认 false）；开启且 provider 已配置时，索引管线对 **IMAGE 类 chunk**（内嵌图、独立上传图、扫描页 page_render——即 t_kb_image_asset 全部三 kind）额外产多模态向量：`embedImages` 原图字节。VLM 文本代理链路**保留不变**（多模态是加路不是换路，文本代理仍进主索引供 BM25/文本向量召回）。
- 物理索引：`{kbId}_mm_{model}`、别名 `{kbId}_mm`，t_kb_index_registry 新行（engine 沿用部署引擎、embedding_provider/model 记多模态款）；双写同步行复用 t_kb_chunk_index_sync（physical_index_name 区分），补偿扫描零改动。
- **引擎边界**：多模态向量仅写向量引擎（qdrant 模式写 Qdrant mm collection；lite/es 模式写 ES `{kbId}_mm` dense_vector 索引）；zero-key（无多模态 Key）→ 开关可存但索引跳过（EmbeddingStatus SKIPPED 先例）。
- 开关翻转 → 计入 chunk 指纹 → config_stale → 重建补齐/清除 mm 向量。

### 6.3 检索侧（第三召回路）
- KB `multimodal_enabled` 且 provider 配置时，文本查询同时 `embedTexts` 进多模态空间查 `{kbId}_mm`（图文同空间，文本可命中图）；
- 融合：mm 路作为第三路参与 **RRF**；`fusion_mode=weighted` 时 mm 路不参与并记 degraded `mm_route_skipped`（先例：graph 与 weighted 互斥的 GraphFusionPolicy 单点，同一处扩展）；
- mm 命中的是 IMAGE chunk（引擎里 chunk_id 同主索引同一行语义），去重靠 chunk_id ——同一 chunk 主路 mm 路都命中时 RRF 天然聚合；
- provider 调用失败/超时 → 跳过 mm 路记 degraded `mm_route_unavailable`，主路不受影响。

## 7. F6 以图搜图入口

- **管理台** `SearchRequest` 增 `images?: string[]`（裸 base64，无 data: 前缀；≤3 张、单张解码 ≤5MB、总量 ≤10MB——与 PublicSearchRequest.images 完全同约束同校验，复用 ImageQueryService 的校验单点）。
- 行为分派（单点在 RetrievalService 编排入口前）：
  1. KB `multimodal_enabled` 且 mm provider 配置 → `embedImages` 直接查 mm 路（图搜图/图搜文），与文本 query 各路结果 RRF 融合；query 可为空字符串？否——`query` 仍必填（空 query 纯图检索本期不做，百炼图片问答也要求文本意图；契约明示）；
  2. 否则 → 回落现状：VisionProvider 转写文本追加进 query（ImageQueryService 既有逻辑，degraded `image_understanding_unavailable` 语义不变）。
- **开放 API**：`PublicSearchRequest.images` 已存在，行为增强同口径（快照 KB multimodal 开启即走 mm 路），请求形状零变化。
- web：检索调试页增图片上传（≤3、缩略图预览、超限前端先拦 + 服务端兜底）；结果卡片 IMAGE chunk 展示已有，无新增。

## 8. kb-rag-web 汇总

- **外部数据源 tab**（知识库详情，先例 WebSourcesTab）：源列表（名称/bucket/prefix/最近同步 Tag/自动同步 Switch/操作：立即同步、测试连接、编辑、删除 Popconfirm"仅移除登记，已入库文档不受影响"）、新建/编辑抽屉（secret 占位提示"留空保留原值"）、对象明细抽屉（items 分页表）。
- **IndexConfigDrawer**：切分策略下拉增三项（SPLIT_STRATEGY_META），条件字段（separator 输入 + 正则开关 / heading level 选择）；父子开关打开时下拉仅 `fixed_length` 可选、其余置灰并给 Alert（与服务端 v1.1 收窄一致，避免填完整表单才在保存时撞 INVALID_PARAM）；"元数据抽取"分组（规则列表编辑：key/类型/值-模式-词表，≤10 条）；"多模态索引"开关（mm provider 未配置时禁用 + Alert）。
- **检索调试页**：rerank 模式选择（semantic/hybrid + w_semantic 滑杆，阈值语义说明文案）、图片上传。
- api 层：`extSource.ts`（七函数）；types.ts 增 ExtSource/ExtSourceItem/MetadataRule/RerankMode 等类型与 META 表。

## 9. kb-rag-deploy 汇总

- OpenAPI kb-server.yaml：ext-sources 7 端点 + schema、SearchRequest/IndexConfig/RetrievalConfig 扩展字段、版本升 **0.14.0-m14**。
- `.env.example` 增：EXT_SOURCE_ 五变量、MULTIMODAL_EMBEDDING_ 四变量、RETRIEVAL_RERANK_MODE、RETRIEVAL_RERANK_W_SEMANTIC。
- CHANGELOG：六特性 + secret 明文存储声明 + degraded 新码（`mm_route_unavailable`/`mm_route_skipped`）。

## 10. 单测清单（必须，离线，精确断言）

- **F1**：ConnectorRouter 未知类型拒；同步语义（etag 未变→UNCHANGED 不触 upload、变了→upload 回填、trashed→SKIPPED、对象消失→SKIPPED 不动文档、单对象失败不中断、超上限→PARTIAL）；派生文件名稳定性/不撞名；secret 脱敏与"空=保留"；定时 disabled 短路。S3 列举/取回 mock MinIO client。
- **F2**：key 规范与保留键冲突拒；三类规则各正/负例（regex 捕获组/无捕获组/未命中不写键、keyword 子集/空不写键、constant 截断）；保留键冲突让位；规则进指纹；custom 过滤映射 ext_ 前缀、非法 key 拒。
- **F3**：separator 字面量/正则/编译失败拒/分隔符不保留；heading 各 level 边界/无标题回落；page 多页/无 pages 单页；三策略超长回落 fixed_length；父子组合仅 fixed_length 放行（llm_semantic/page 均拒）；page_no 入 metadata。**枚举与实现的对应关系单测化**：每个可保存的策略码都必须路由到同名实现（防"配置得上、跑的是定长"），page 策略消费清洗后正文（脱敏生效、图片可关联、无规则时与 parser markdown 逐字符相等），各策略分片指纹两两不等。
- **F4**：hybrid 排序分公式（含无 bm25 分按 0、min-max 单点集退化）；阈值仍作用语义分；rerank 降级时 hybrid 行为同现状；参数五层优先级。
- **F5**：开关+provider 齐备才产 mm 向量；zero-key SKIPPED；registry/同步行落 mm 索引名；weighted 模式 mm 路跳过记 degraded；provider 失败降级不影响主路；开关进指纹。
- **F6**：mm 可用走 embedImages、不可用回落 VLM 转写；images 约束校验复用单点（超张数/超体积拒）；query 仍必填。
- **回归红线**：全部新字段缺省时，现有单测零修改通过（兼容承诺的机器验证）。

## 11. 验收清单（实现完成后用户自测）

1. 登记本地 MinIO bucket（放 pdf/docx/图片各一）→ 异步同步后文档全部 INDEXED 可检索；改对象重传 → sync 产生新版本；删对象 → sync 后文档仍在（SKIPPED）；secret 列表恒 `******`。
2. 配置 metadata_rules（合同编号 regex + 产品词表）→ 重建后 chunk metadata 可见抽取值 → 检索 `custom:{contract_no:...}` 精确命中。
3. 五种新旧切分策略（fixed_length/llm_semantic/separator/heading/page）各建一库导同一文档，切片边界符合各自语义；按页切分的库导一份带图带手机号的 PDF，分片正文里手机号已脱敏、含图页分片能看到图片缩略图；父子开关打开时下拉只剩定长切分。
4. hybrid 模式下关键词强匹配文档排序上升；阈值过滤行为与 semantic 模式一致。
5. 开启 multimodal 的库上传扫描件与图片 → 文本 query 可命中图片 chunk（mm 路 RRF 融合可在 debug 页看到 retrieval_source）。
6. 检索调试页传图 → mm 库直接图搜图命中原图 chunk；非 mm 库回落 VLM 转写仍有结果；开放 API images 同口径。
7. `mvn -B -ntp verify` 全绿；`pytest` 全绿（parser 本期无改动，跑回归）；oxlint/tsc 全绿。
