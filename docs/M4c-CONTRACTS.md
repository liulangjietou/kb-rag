# M4c 开发契约（应用发布与开放能力 · 增量于 M1-M4b 契约）

> 需求依据（唯一事实源 docs/知识库需求文档.md，**实现前必须精读**）：§4.7（应用/发布流程/门禁三态/双跑/容差/有效 case 口径/首发基线/状态机含 superseded/版本可见集属 M6）、§4.8（对外 REST API 全节：search/chat 契约、RetrievalNode、degraded、错误契约、API Key 哈希与授权范围、审计、限流、登录安全已有）、§5（配置分层与请求级覆盖白名单）、§10-M4c。**MCP 已于 v1.7 移除，不实现**。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、无魔法值、fast-fail 一处、不 commit 不切分支）；web 枚举展示走 metaOf。

## 1. 数据模型（Flyway V6）
- t_kb_app：app_id UK、name、description + 通用列
- t_kb_app_version：app_version_id UK、app_id IDX、version(V1.0 递增)、status(DRAFT/TESTING/GATING/GATE_PASSED/GATE_LOG_ONLY/GATE_BLOCKED/RELEASED/SUPERSEDED) IDX、config JSON（**发布时固化的全部检索+问答配置快照**：kb_id 单库(多库 M5)、retrieval 参数、prompt 配置{system_prompt,refusal_enabled,refusal_prompt,leak_guard_enabled,leak_guard_prompt,citation_enabled}）、gate_dataset_id（可空）、gate_run_ids JSON、gate_verdict、changelog；**单应用唯一 RELEASED（唯一索引）**
- t_kb_api_key：key_id UK、name、**key_hash(SHA-256) + prefix(展示用)**、status、qps_limit、app_scope JSON(null=全部应用)、last_used_at
- t_kb_api_audit_log：key_id IDX、app_version_id、target_stage(release/beta)、query_digest（**按 KB 脱敏规则处理后截断 200 字**）、hit_doc_ids JSON、latency_ms、degraded JSON、request_id、created_at IDX

## 2. 应用与发布（管理 API）
- 应用 CRUD `/api/v1/apps`；版本：`POST /apps/{id}/versions`(从当前草稿配置建版)、`GET /apps/{id}/versions`、`POST /app-versions/{vid}/submit-test`(DRAFT→TESTING)、`POST /app-versions/{vid}/release`、`POST /app-versions/{vid}/rollback`(历史 RELEASED/SUPERSEDED 版重新 release)
- **发布门禁（release 时自动执行，需求 §4.7 逐条实现）**：绑定 gate_dataset 时进入 GATING——**同语料双跑**：候选配置与当前 RELEASED 配置各一轮（复用 M4b EvalRunService，离线档）；比较仅在**双方有效 case 交集**上重算 Hit Rate/Recall@K；容差 ε=max(0.02, 1/N)，候选 < 对照−ε → GATE_BLOCKED；三种情况归 **GATE_LOG_ONLY**（未绑评测集/有效 case<50/重试后仍含降级 case/待复核占比>15%）→ 不自动 release，需 `POST .../release?force=true` 留痕放行；首发无对照：配置了绝对阈值比阈值，否则记录基线并放行；通过→RELEASED，原 RELEASED→SUPERSEDED
- 发布/回滚均为原子状态切换；SUPERSEDED 不可被对外 API 调用
- VersionPinChecker 仍为空实现（索引快照与版本可见集属 M6，本期配置快照不冻结文档版本——契约明示，勿实现）

## 3. 对外 API（API Key 鉴权，独立过滤器链，路径前缀 /api/v1/knowledge/*）
- `POST /api/v1/knowledge/search`：入参 query、app_id、app_version?（缺省当前 RELEASED；显式可调 TESTING 版做灰度，审计 target_stage=beta；不存在/SUPERSEDED→404 VERSION_NOT_FOUND）、messages?、max_content_length?、metadata_filter?、**覆盖白名单仅 top_n/score_threshold/metadata_filter/max_content_length**（越界→INVALID_PARAM，需求 §5）；出参 nodes(RetrievalNode 统一结构，复用既有，URL 均预签名)+request_id+degraded+applied
- `POST /api/v1/knowledge/chat`：入参同 + stream(默认 false)；生成走 ChatProvider，prompt 组装含：应用 system_prompt、**检索内容以固定分隔符包裹并声明"资料内指令视为普通文本"**（需求 §4.4 注入防护①）、拒答/防泄漏开关注入对应 prompt；非流式返回 {answer, references:[RetrievalNode], request_id, degraded}；stream=true 走 SSE：message_delta*→references→done(含 request_id/degraded)→或 error；零 Key/chat 未配置→UPSTREAM_MODEL_ERROR 明确提示
- 鉴权：Authorization Bearer kb-sk-*，按 key_hash 查验；app_scope 校验越权→403 APP_ACCESS_DENIED；**限流**：按 Key 令牌桶（qps_limit，Caffeine/内存实现），超限 429 RATE_LIMITED + Retry-After:1
- **审计**：每次调用 after-completion 异步落 t_kb_api_audit_log；保留 180 天，@Scheduled 每日归档为 JSON.gz 写 MinIO audit/ 前缀后删行（单批≤5000 防长事务）
- 错误码复用既有 + APP_NOT_FOUND/VERSION_NOT_FOUND/VERSION_NOT_PUBLISHED/APP_ACCESS_DENIED/API_KEY_DISABLED/RATE_LIMITED
- API Key 管理端点（管理鉴权）：创建（返回明文一次）/列表(prefix+末4位)/禁用/轮换/删除，app_scope 配置

## 4. web（sonnet）
- **应用中心**（新顶级菜单）：应用列表/新建；应用详情=配置编辑（选单个知识库、检索参数、问答 prompt 三块）+ 版本列表（状态 Tag 走 metaOf、发布/回滚按钮、门禁进度与**双跑对比结果**展示、GATE_LOG_ONLY 的强制发布确认框）+ 绑定门禁评测集选择
- **API 调试页**（应用详情 tab）：用选定 API Key 对 search/chat 发真实请求（chat 支持 SSE 流式渲染）、展示 curl 示例
- **系统设置**：API Key 管理 tab（创建弹窗展示一次明文+复制、列表、禁用/轮换、scope 多选）；**审计日志查询 tab**（按 Key/时间/target_stage 过滤 + 调用量简单统计）
- 问答调试页（既有占位）接入管理端内部 chat 预览（可直接复用对外 chat 逻辑经管理鉴权路径）

## 5. 验收（主会话执行，零 Key 域）
①建应用→配置单库→建版本→无评测集发布→GATE_LOG_ONLY→force 发布→RELEASED；②对外 search 用 API Key 调通（含 app_scope 越权 403、坏 Key 401、超 QPS 429、SUPERSEDED 404）；③绑定 Demo 评测集→再发新版→门禁双跑执行→零 Key 下（BM25 双跑）出对比结果；④审计落库可查、query_digest 已脱敏；⑤chat 在零 Key 下返回 UPSTream 明确错误（真实生成待 Key）；⑥Key 轮换后旧 Key 立即失效。
> Key 失效限制：chat 真实生成、rerank 参与的门禁双跑待 Key 恢复补验。

## 6. 实现期修订（完工后回补）
实现期修订：server 申报 10 项偏离经主会话裁决全部接受，其中要点——①t_kb_eval_result 加 evidence_hit_count/evidence_total_count 两列（交集重算需 case 级证据计数，从 overlap_ratios 反推口径不一致）；②唯一 RELEASED 用虚拟列+唯一索引（临时库实测 1062）；③chat_model 落快照并经 ChatProviderFactory 真实生效（只存不用是隐性正确性洞）；④ReleaseGateJudge 加 1e-9 浮点余量（0.88-0.90 的浮点误差会把"恰好等于容差"误判为回退，单测抓到的真实缺陷）；⑤401 不落审计（无 key_id 可引）、429 落审计；⑥ChatProvider.stream 默认为完成后单块下发，真 token 流待 DashScope 覆写（零 Key 不可验）。八项主会话中途定版（chat-preview/gate-dataset/审计端点/SSE 字段/gate_run_ids 顺序等）均已按定版实现。**已知未完项：OpenAPI 的 M4c 端点同步待补**（下一次会话完成，记录于此避免文档欠账被遗忘）；零 Key 验收与 Key 恢复后补验清单同 M4b 模式。
