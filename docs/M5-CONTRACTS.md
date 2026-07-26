# M5 开发契约（多知识库路由 · 增量于 M1-M4c 契约）

> 需求依据（唯一事实源 docs/知识库需求文档.md）：§4.9（路由：LLM 判断 query 该查哪些库、Prompt 与开关界面化、**路由输出必须命中候选库 ID 白名单，未命中降级检索全部关联库并记 degraded=route_fallback_all**——§4.4 注入防护③）、§4.7（跨库融合规则：每库独立各路召回与库内融合 → 跨库按库内排名 RRF → **知识库配额权重按比例分配 rerank 候选总上限（全局默认 50，非每库）**，向下取整余量给权重最高库 → rerank 统一候选集出最终排序）、§10-M5（验收：多库应用能按 query 正确选库，跨库结果按配额合并）。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 带错误码、lombok、无魔法值、fast-fail 一处、不 commit/不切分支）；web 枚举走 metaOf。

## 1. 数据模型（无新表）
- `t_kb_app_version.config` 的 JSON 扩展：`kb_id` → `kb_refs: [{kb_id, weight}]`（权重正整数，默认 1；**读侧兼容旧快照的单 kb_id 字段**，视为 `[{kb_id, weight:1}]`）；新增 `routing: {enabled(默认 false), prompt(可空=用内置默认路由 prompt)}`
- M4c 的"应用限单库"解除：应用配置允许挂 1..15 个库（需求上限 15）

## 2. kb-rag-server
### 2.1 路由（RoutingService，kb-app）
- 开启且应用挂 ≥2 库时执行：ChatProvider 一次调用，prompt = 路由指令 + 各候选库的 {kb_id, name, description}，要求仅返回 JSON 数组形式的 kb_id 列表
- **输出强校验**：解析结果与候选白名单求交集；为空/解析失败/超时（复用 CHAT_TIMEOUT_MS，评测中走离线档）→ 降级检索全部关联库，degraded += `route_fallback_all`
- 未配置 chat 模型：路由自动跳过（等同关闭，不加 degraded；显式开启但无模型时加 `route_fallback_all`）；单库应用不调用路由
- Caffeine 缓存（query+候选集 hash，TTL 10min）

### 2.2 跨库检索编排（扩展 RetrievalService 或其上层，复用现有单库链路）
- 每个选中库：独立执行既有"多路召回+库内融合"（复用 RetrievalService 的库内部分，**不复制逻辑**——必要时把库内环节抽成可复用方法）
- 跨库合并：**基于库内排名的 RRF**（k 复用现有 rrf_k）产生统一序
- **配额分配**：rerank 候选总上限（现有 50 配置）按 kb_refs 权重比例分配到各库，向下取整、余量给权重最高库；各库贡献超配额的截断
- rerank（开启时）在配额合并后的统一候选集执行，rerank 分为最终排序；阈值与 top_n 逻辑不变；父子归并仍按库内子片→父片规则
- applied 增 `routed_kb_ids`（本次实际检索的库）；对外/管理 search 的响应 node.metadata 增 `kb_id`
- 管理端调试 search 仍是单库端点不变；**多库链路由应用（chat-preview 与对外 knowledge/search、knowledge/chat）触达**
- 评测（M4b）不涉及多库（评测集绑定单库），不改

### 2.3 单测（必须，精确断言）
路由输出白名单交集/空交集降级/解析失败降级/单库不调用；配额分配（权重 2:1:1 分 50 → 26/12/12 类的向下取整+余量归属；权重相等；单库全额）；跨库 RRF 合并顺序；kb_refs 旧快照兼容读取

## 3. kb-rag-web
- 应用配置编辑：单库选择改为**多库列表（1..15，每行 库选择+权重输入，增删行）**；新增"知识库路由"开关 + 路由 Prompt 文本域（留空提示用内置默认）
- 版本详情/列表展示 kb_refs 摘要；API 调试与 chat 预览无 UI 变化（响应里 routed_kb_ids 在 applied 信息条展示、node 显示所属库名）
- 类型层同步 kb_refs/routing；枚举走 metaOf

## 4. kb-rag-deploy（主会话收尾）
- **补 M4c 欠账**：OpenAPI 同步 M4c 全部端点（apps/versions/gate/chat-preview/api-keys/audit-logs/knowledge search+chat）
- OpenAPI 同步 M5 变更（kb_refs/routing/routed_kb_ids）；CHANGELOG；契约回补

## 5. 验收（主会话，零 Key 域 + 清 M4c 欠账）
A. **M4c 补验六项**：建应用→无评测集发布→GATE_LOG_ONLY→force→RELEASED；对外 search 调通含 401/403(scope)/429/404(SUPERSEDED)；绑定 Demo 评测集发新版→零 Key BM25 双跑出对比；审计落库且 query_digest 脱敏；chat 零 Key 明确 UPSTREAM_MODEL_ERROR；Key 轮换旧 Key 失效
B. **M5**：应用挂两库（不同内容）→ 路由关：两库都查、配额按权重生效（node.metadata.kb_id 覆盖两库）；路由开+零 Key：degraded 含 route_fallback_all 且仍全库检索；权重 3:1 时 rerank 候选配额 38/12（无 rerank 时验证进入合并的候选数）；旧单库快照应用仍可正常调用（兼容读）
> Key 失效限制：LLM 真实路由选库、rerank 参与的跨库排序待 Key 恢复补验。

## 6. 实现期修订（完工后回补）

**主会话中途定版七条**（web 先完成暴露，server 按此实现，两侧已核对一致）：①config 键名 `kb_refs`/`routing`，routing 整体可缺省=关闭，旧单 `kb_id` 兼容读收敛在 `AppConfigSnapshot.getKbRefs()` 一处（`kb_id` 标 WRITE_ONLY 只进不出）；②kb_refs 权威校验单点在 `AppVersionService.requireUsableKbRefs()`（1..15、不重复、正整数权重、库存在→INVALID_PARAM），上限走配置 `kb.retrieval.max-linked-kb`；③search 类响应 `applied.routed_kb_ids`；④chat 为**顶层** `routed_kb_ids`（非嵌套 applied，M4c ChatResponse 本无 applied 包装），SSE `done` 事件与 request_id/degraded 并列；⑤多库编排产出的 node 一律填 `metadata.kb_id`；⑥gate-dataset 校验放宽为「数据集所属 kb ∈ 版本 kb_refs 集合」，EvalRunService 零改动；⑦degraded 枚举沿用 `route_fallback_all`。

**server 申报偏离六条，主会话裁决全部接受**：①管理端单库 /search 也填 `metadata.kb_id` 与 `applied.routed_kb_ids`（纯新增键、与本契约 §2.2 原文一致，避免同一元数据双路径不一致）；②多库时 `applied.fusion_mode` 如实返回 `rrf`（最终排序确由跨库 RRF 产生，与 `score_type` 同源，不谎报配置值）；③快照序列化恒出 `kb_refs` 不回写 `kb_id`（单一事实源，web 读旧版本详情也拿到 kb_refs）；④`createDraft` 保存即校验 kb_refs（fast-fail 提前，可观测变化：携带任一 config 字段的建版本请求必须带 kb_refs 或 legacy kb_id）；⑤多库时库级单值默认（retrieval 参数、rewrite/rerank 开关）取**声明第一个库**，胜者由运维声明顺序决定；⑥配额只在实际出候选的库间分配（空库不占预算）。

**web 侧约定**：兼容读唯一入口 `utils/kbRefs.ts` 的 `resolveKbRefs()`；kb_id→名称防御查找 `kbNameOf()`（回退原始 id）；门禁评测集选择器按 kb_refs 并集取数据集、多库时"库名·数据集名"消歧；管理端单库调试页不渲染 kb Tag。

**实现新增配置键**（application.yml 已接环境占位符，.env 可覆盖）：`kb.retrieval.max-linked-kb=15`（RETRIEVAL_MAX_LINKED_KB）、`kb.retrieval.routing-cache-ttl-minutes=10`（RETRIEVAL_ROUTING_CACHE_TTL_MINUTES）、`kb.retrieval.routing-cache-max-size=10000`（RETRIEVAL_ROUTING_CACHE_MAX_SIZE）。

**验收结果（2026-07-26，零 Key 域，20010 临时实例）**：§5A 六项全过——无评测集发布→GATE_LOG_ONLY→force→RELEASED；对外 search 调通含 401(INVALID_API_KEY)/403(APP_ACCESS_DENIED)/429(Retry-After:1)/404(SUPERSEDED→VERSION_NOT_FOUND)；Demo 评测集双跑出对比（gate_run_ids=[候选,对照]，零 Key BM25，INSUFFICIENT_CASES→LOG_ONLY 符合预期）；审计落库且 query_digest 脱敏（`138****8000`）；chat 零 Key 明确 UPSTREAM_MODEL_ERROR；Key 轮换旧 Key 立即 401。§5B 四项全过——双库路由关两库都查且 node.metadata.kb_id 覆盖两库；路由开+零 Key degraded=[route_fallback_all,…] 仍全库检索；权重 3:1 配额日志实测 `quotas={38, 12}, merged=50`（等权双库 25/25）；SQL 构造 M4c 旧单库 config 的 RELEASED 版本对外调用正常且 routed_kb_ids=[该库]。单测 553 项（新增 53）全过。Key 恢复后补验：LLM 真实选库、rerank 参与的跨库排序。
