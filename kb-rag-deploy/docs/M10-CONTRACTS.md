# M10 开发契约（检索质量闭环 · 增量于 M1-M9 契约）

> 需求依据：知识库需求文档 §4.5（检索结果反馈"沉淀为评测集素材"——M4b 只落了 GOOD 的收集端点、BAD 仅打日志，本期把反馈真正持久化并闭环到评测集）、§4.6（评测体系，本期不改运行器）、§4.7（发布门禁已于 M4c 完整落地，本期**不重复建设**）。新增能力：**检索洞察（未命中/降级分析）**——管理台检索与开放 API 检索均落洞察行，聚合出"内容缺口报表"，回答"用户在搜什么却搜不到"。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 一处、不主动 commit）；web 枚举展示走 metaOf。

## 0. 范围与边界

- **本期做**：①反馈持久化与管理（列表/转评测用例/忽略）；②检索洞察记录与聚合报表（零命中、降级、Top 未命中 query 分组）；③洞察数据保留期清理。
- **本期不做**：发布前评测卡点（M4c ReleaseGateService 已交付双跑+三态裁决+容差，验收沿用）；开放 API 的最终用户反馈端点（需要终端用户身份，属权限体系后期）；洞察数据 MinIO 归档（保留期直接删，量级与审计不同）。
- **兼容红线**：`POST /api/v1/retrieval-feedback` 入参 payload 不变（`kb_id/query/chunk_id/verdict`），行为从"仅日志"升级为"落库"；前端旧调用无需改动即获得持久化。

## 1. 数据模型（Flyway V12）

| 表 | 列 |
|---|---|
| t_kb_retrieval_feedback | `feedback_id` UK、`kb_id` IDX、`query` TEXT（原文，供转 case 用）、`chunk_id`、`doc_id`（服务端由 chunk 反查冗余，chunk 已删时可空）、`verdict`(GOOD/BAD)、`status`(NEW/CONVERTED/DISMISSED) IDX、`converted_case_id`（可空，转换后回填）、`note`（可空）+ 通用列；复合索引 `idx_kb_status(kb_id, status)` |
| t_kb_search_insight | `insight_id` UK、`kb_id` IDX、`source`(CONSOLE/OPEN_API)、`query_digest` VARCHAR(200)（**按 KB 脱敏规则处理后截断，绝不存原文**，与 t_kb_api_audit_log 同口径）、`query_hash` CHAR(64) IDX（归一化后 SHA-256，未命中分组键：trim + 小写 + 连续空白折叠）、`result_count`、`top_score` DOUBLE（可空，首节点分数）、`zero_hit` TINYINT（`result_count=0` 派生冗余，聚合扫描列）、`degraded` JSON（可空）、`request_id` + 通用列；复合索引 `idx_kb_zero_created(kb_id, zero_hit, created_at)` |

- `t_kb_eval_case.source` 枚举增 `FEEDBACK`（varchar 列无 DDL；CaseSource 枚举与 web metaOf 同步增）
- 洞察行**不含用户身份**（当前无用户体系），request_id 供与日志/审计关联

## 2. kb-rag-server

### 2.1 反馈闭环（RetrievalFeedbackService，kb-app 新包 feedback）
- `POST /api/v1/retrieval-feedback`：落库（GOOD 与 BAD 均落）；服务端由 chunk_id 反查 doc_id（chunk 不存在不拒绝——反馈可能晚于分片删除，doc_id 置空并 info 日志）；重复提交不去重（反馈是事件不是状态）
- `GET /api/v1/kb/{kbId}/retrieval-feedback?verdict=&status=&page=&size=`：分页列表，最新优先（分页默认/上限沿用 20/200 惯例）
- `POST /api/v1/retrieval-feedback/{feedbackId}/convert`：`{dataset_id}` → 复用 `EvalDatasetService.collectFromRetrieval` 建 case（`source=FEEDBACK`），成功后 `status=CONVERTED` 且回填 `converted_case_id`；仅 `status=NEW` 可转，`verdict=BAD` 不可转（BAD 的价值在洞察与统计，不构成正向证据）；chunk 已删 → INVALID_PARAM 明确提示
- `POST /api/v1/retrieval-feedback/{feedbackId}/dismiss`：`status=DISMISSED`；仅 NEW 可忽略
- 状态机：`NEW → CONVERTED | DISMISSED`，终态不可再变（重复调用 → INVALID_PARAM）

> **租户解析义务（M16 后修复补齐）**：`t_kb_retrieval_feedback` 是经 `kb_id` 归属租户的从属表，不带 `tenant_id` 也不进行级围栏。上面四个端点原先只过 `KbScopeGuard#requireFeedbackAccess` 或 `AccessGuard.requireKbAccess(kbId)`，两者都只回答数据范围、一行租户判断都没有：凭一个 `kbId` 能列出别家知识库的全部反馈（**其中带原始 query 文本**），凭一个 `feedbackId` 能转/忽略别家的反馈行，`POST` 还能往别家的反馈队列里塞行。现由 `KbResourceGuard` 与 `RetrievalFeedbackService#require`/`#list`/`#record` 一律先解析根表 `t_kb_knowledge_base`，跨租户读作"不存在" → **404**；`kbId` 入口改用 `requireKb`，把租户判定摆回数据范围判定之前（原先顺序反了，403 与 404 的差异会泄露"这个 id 在别的租户里存在"）。**开放端反馈（`Bearer kb-sk-*`）不受影响**：那条链由 `request_id` 反查洞察行、再经 `ApiKeyPrincipal#requireAccessTo` 校验 Key 的授权范围，是既有语义。详见 `M16-CONTRACTS.md` §1.3.2。

### 2.2 检索洞察（SearchInsightService，kb-app 新包 insight）
- **记录点在 API 边界而非 RetrievalService 内部**：评测运行、门禁双跑复用同一检索链路，若埋在链路内会把离线跑污染进报表——
  - 管理台调试：`SearchController` 检索成功返回后异步记录（source=CONSOLE）
  - 开放 API：`KnowledgeApiService` 的 search 与 chat 检索完成后异步记录（source=OPEN_API；chat 记录其检索环节的 result_count）；**被拒绝的调用不记洞察**（审计已覆盖）
- 异步写复用 `AUDIT_EXECUTOR`；写失败仅 error 日志，绝不影响检索响应（与 ApiAuditService 同原则）
- `GET /api/v1/kb/{kbId}/search-insights?zero_hit=&from=&to=&page=&size=`：分页明细，最新优先
- `GET /api/v1/kb/{kbId}/search-insights/stats?from=&to=`：聚合报表 `{total, zero_hit_count, zero_hit_rate, degraded_count, top_zero_hit_queries:[{query_digest, count, last_at}]}`——Top 分组按 `query_hash` group by 取 count 前 10，query_digest 取组内最新一条；时间窗缺省近 7 天
- 保留期清理：`@Scheduled` 每日删除 `kb.insight.retention-days`（默认 90，INSIGHT_RETENTION_DAYS）之前的行，单批 ≤5000 防长事务（沿用审计归档惯例，无 MinIO 归档）

> **租户解析义务（M16 后修复补齐）**：`t_kb_search_insight` 是经 `kb_id` 归属租户的从属表，不带 `tenant_id` 也不进行级围栏。上面两个报表端点原先只过 `AccessGuard.requireKbAccess(kbId)`，那只回答"这个库在不在调用者角色配的数据范围里"，而 `kb_scope_all` 对五个内置角色恒为真——**报一个别家的 `kbId`，后续按 `kb_id` 过滤的语句照常执行**，别家用户搜过什么（洞察行存的是原始 query 文本）连同零命中与降级分布一并读出。现由 `SearchInsightService#list`/`#stats` 首行 `knowledgeBaseService.require(kbId)` 先解析根表，跨租户读作"不存在" → **404**；Controller 那行也换成 `KbResourceGuard#requireKb`，把租户判定摆回数据范围判定之前。**记录侧不受影响**：`recordAsync` 跑在无控制台主体的线程上，知识库由调用链传入，是既有语义。详见 `M16-CONTRACTS.md` §1.3.2。

### 2.3 配置键（application.yml 接环境占位符，KbProperties 承载）
- `kb.insight.retention-days=90`（INSIGHT_RETENTION_DAYS）
- `kb.insight.enabled=true`（INSIGHT_ENABLED，false 时记录点直接短路，报表端点照常可查历史）

### 2.4 单测（必须，精确断言）
- 反馈：GOOD/BAD 均落库且 doc_id 反查、chunk 缺失落库不拒绝、convert 委托 collectFromRetrieval 且回填 case id、BAD 不可转、DISMISSED/CONVERTED 终态重复操作被拒、状态机全路径
- 洞察：query_hash 归一化（大小写/空白折叠等价、不同 query 不同 hash）、zero_hit 派生、digest 脱敏截断复用 QueryDigestFactory、stats 聚合（零命中率、Top 分组排序与取最新 digest）、enabled=false 短路、清理任务边界（保留期内不删）
- 记录点：评测/门禁路径不产生洞察行（构造 RetrievalService 直调不触发记录的断言）

## 3. kb-rag-web

- 检索调试页：好/坏按钮沿用现端点（行为自动升级为落库），BAD 提交后 toast 文案改"已记录，可在反馈管理查看"
- 知识库详情新增两个入口（沿用现有 tab/菜单惯例）：
  - **反馈管理**：列表（verdict/status 筛选）+ 行操作"转入评测集"（评测集选择弹窗）/"忽略"；status 走 metaOf
  - **检索洞察**：统计卡片（总检索/零命中率/降级次数）+ Top 未命中 query 表 + 明细列表（zero_hit 筛选、时间范围）
- api 层：`retrievalFeedback.ts` 扩展列表/convert/dismiss；新增 `searchInsight.ts`；types.ts 增 RetrievalFeedbackEntry/SearchInsightEntry/SearchInsightStats/CaseSource 增 FEEDBACK

## 4. kb-rag-deploy（收尾）

- OpenAPI kb-server.yaml：retrieval-feedback 语义更新 + 新增 5 个端点与 schema；版本升 0.11.0-m10
- .env.example 增 INSIGHT_RETENTION_DAYS/INSIGHT_ENABLED；CHANGELOG 记录

## 5. 验收

1. 调试页点"坏"→ 反馈管理出现 NEW/BAD 行；点"好"后转入评测集 → case source=FEEDBACK 且反馈行 CONVERTED
2. 发起零命中检索（无关 query）→ 洞察明细出现 zero_hit 行，stats 的 top_zero_hit_queries 含该 query 的脱敏摘要；同 query 大小写变体命中同一分组
3. 评测运行全程不产生洞察行
4. `mvn -B -ntp verify` 全绿
