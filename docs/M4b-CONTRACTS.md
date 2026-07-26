# M4b 开发契约（评测体系 · 增量于 M1/M2/M3/M4a 契约）

> 需求依据：知识库需求文档 §4.3（LLM 语义切分）、§4.5（评测集标注/锚定类型/待复核/多轮 case）、§4.6（评测功能全节）、§5（Demo 示例评测集导入，M3 移交）、§6（四张评测表）、§7（评测中心）、§10-M4b。
> 全局约定沿用 M1-CONTRACTS §0（端口 20000/20001/20002、英文注释、**每个类必须带 `@author owlzhangfq@gmail.com`**、日志仅 info/error 英文带错误码占位符、lombok、CollectionUtils 判空、无魔法值、fast-fail 一处、LLMentor 代码红线、**不要 git commit / 不要切分支**）。
> 前端约定：枚举展示一律走 `metaOf`，禁止直接索引映射表。

## 0. 范围与两处边界

- **本期做**：评测集与证据标注、证据复核工作台、评测任务与报告、LLM 语义切分策略、Demo 示例评测集导入、把 M4a 留的两个占位填实。
- **本期不做**：**评测门禁**（发布前双跑与拦截）属 M4c，M4b 只交付"能跑评测、能出报告"；应用与版本发布同属 M4c。
- **M4a 留下的两个占位，本期必须填实**：
  1. `GET /documents/{docId}/versions/{versionId}/activate-impact` 的 `affected_eval_case_count`（M4a 恒返回 0）→ 改为真实统计"锚定该 doc_id 且会因本次切换进入待复核的 case 数"
  2. 文档激活版本切换时**同步扫描**锚定该 doc_id 的全部 span 级 case，证据在新激活版本中匹配不上的置 `evidence_stale`（需求 §4.5 待复核触发时机）

## 1. 数据模型（Flyway V5）

| 表 | 列 |
|---|---|
| t_kb_eval_dataset | `dataset_id` UK、`kb_id` IDX、`name`、`description`、`dataset_revision` INT（case 增删改即 +1，需求 §4.6 门禁可比性依据）、`case_count`（派生冗余，随增删维护）+ 通用列 |
| t_kb_eval_case | `case_id` UK、`dataset_id` IDX、`query`、`messages` JSON（可空，多轮历史）、`expected_answer`（可空）、`anchor_type`(SPAN/DOCUMENT)、`evidences` JSON（数组：`{doc_id, span, annotated_version_id}`；DOCUMENT 锚定时 span 为空）、`status`(ACTIVE/EVIDENCE_STALE/DEPRECATED) IDX、`source`(MANUAL/DEBUG_PAGE/IMPORTED)、`note` + 通用列 |
| t_kb_eval_run | `run_id` UK、`dataset_id` IDX、`kb_id`、`dataset_revision`（跑时快照）、`corpus_fingerprint`（激活版本集合 hash + 嵌入版本 + 词典版本，需求 §4.6）、`retrieval_config` JSON（本次跑用的检索配置）、`judge_model`/`judge_prompt_version`（可空）、`status`(PENDING/RUNNING/SUCCESS/FAILED) IDX、`metrics` JSON（各 K 的指标 + 95% CI）、`case_total`/`case_effective`/`case_stale`/`case_degraded`、`fail_reason`、`started_at`/`finished_at` + 通用列 |
| t_kb_eval_result | `result_id` UK、`run_id` IDX、`case_id` IDX、`hit`(0/1)、`hit_rank`（首个命中的名次，未命中为 null）、`overlap_ratios` JSON（每条 evidence 的最佳重叠率）、`recalled_chunk_ids` JSON、`degraded` JSON（本 case 的降级标记）、`retry_count`、`judge_score`（可空）、`judge_reason`（可空）+ 通用列 |

- `t_kb_eval_case.status` 与 M4a 的 `t_kb_annotation.inherit_status` 无关，勿复用枚举
- 四表均带通用列（id/created_at/updated_at/lock_version/deleted）

## 2. 评测集与 case 管理（kb-rag-server）

| 端点 | 说明 |
|---|---|
| `POST /api/v1/kb/{kbId}/eval-datasets` | 建评测集 `{name, description}` |
| `GET /api/v1/kb/{kbId}/eval-datasets` | 列表（含 case_count、dataset_revision、最近一次 run 摘要） |
| `GET\|DELETE /api/v1/eval-datasets/{datasetId}` | 详情/删除（删除级联 case 与 run/result，走软删 + after-commit 清理，参照 §3 删除级联原则） |
| `POST /api/v1/eval-datasets/{datasetId}/cases` | 新增 case：`{query, messages?, expected_answer?, anchor_type, evidences:[{doc_id, span}], note?}`；`annotated_version_id` 由服务端按该 doc 当前激活版本填入；写入后 `dataset_revision+1` |
| `GET /api/v1/eval-datasets/{datasetId}/cases?status=&page=` | 分页列表 |
| `PUT\|DELETE /api/v1/eval-cases/{caseId}` | 编辑/删除（均 `dataset_revision+1`） |
| `POST /api/v1/eval-cases/{caseId}/recheck` | 复核：`{action:REANCHOR\|DEPRECATE, evidences?}`；REANCHOR 用新证据替换并置回 ACTIVE、DEPRECATE 置 DEPRECATED |
| `GET /api/v1/eval-datasets/{datasetId}/stale-cases` | 证据复核工作台数据：待复核 case 列表 + 每条的失配证据与当前激活版本中的候选原文（按 §4 重叠率取 Top3 候选供人工选择） |
| `POST /api/v1/kb/{kbId}/eval-datasets/import-demo` | 导入 deploy 仓 `demo/eval-cases.json`（M3 移交）：按 `file_name + content_hash_sha256` 匹配该库文档得 doc_id（匹配不到的 case 跳过并在响应列出），幂等（同名评测集已存在则返回其 id 不重复导入） |

**从检索调试页一键收进评测集**（需求 §4.5）：
- `POST /api/v1/eval-datasets/{datasetId}/cases/from-retrieval`：`{query, messages?, chunk_ids:[...], anchor_type?}` → 服务端读取这些 chunk 的正文作为 span 证据、其 doc_id 作为锚定文档；`source=DEBUG_PAGE`
- 图片类 chunk（`chunk_type=image`）或调用方显式传 `anchor_type=DOCUMENT` → 建成文档级锚定 case（需求 §4.5：图片 case 的 span 锚定不成立）

**检索结果反馈标注**（需求 §4.5）：`POST /api/v1/retrieval-feedback` `{kb_id, query, chunk_id, verdict:GOOD|BAD}` → 落 `t_kb_system_config` 不合适，本期落到 `t_kb_eval_case` 之外的轻量表**不新增**：改为直接以 `source=DEBUG_PAGE` 的 case 草稿承载——`verdict=GOOD` 即一键收进评测集（同上端点），`verdict=BAD` 记 info 日志且不落库。**理由**：需求原文是"沉淀为评测集素材"，GOOD 已由收集端点覆盖，为 BAD 单独建表在本期无消费方。

## 3. 评测运行与报告（kb-rag-server）

### 3.1 运行入参与配置矩阵
- `POST /api/v1/eval-datasets/{datasetId}/runs`：
  `{k:5, configs:[{label, mode:BM25_ONLY|VECTOR_ONLY|HYBRID|HYBRID_RERANK, recall_top_k?, top_n?, fusion?, score_threshold?, rewrite_enabled?}], judge?:{enabled, model?}}`
  - `configs` 允许 1..6 组，一次提交产生 **N 个 run**（每组一个 run，共享 dataset_revision 与 corpus_fingerprint，报告可横向对比）——需求 §4.6"同一评测集下对比多种检索配置"
  - `mode` 映射到既有检索参数：BM25_ONLY（禁向量路）、VECTOR_ONLY（禁 BM25 路）、HYBRID（双路+融合、rerank 关）、HYBRID_RERANK（双路+融合+rerank）
  - **零 Key 环境**：VECTOR_ONLY/HYBRID/HYBRID_RERANK 无法真实执行 → run 直接置 FAILED 且 `fail_reason` 明确说明"嵌入模型未配置"，不产生误导性指标
- `GET /api/v1/eval-runs/{runId}` → run 详情含 metrics；`GET /api/v1/eval-datasets/{datasetId}/runs?page=` → 历史列表
- `GET /api/v1/eval-runs/{runId}/results?hit=&page=` → 每条 case 的命中明细（下钻用）
- `GET /api/v1/eval-runs/compare?run_ids=a,b,c` → 指标对比表（同 dataset_revision 才允许对比，不同则响应 `comparable=false` 并给出原因，需求 §4.6 语料变化标注）

### 3.2 命中判定（严格按需求 §4.6，不得自行简化）
- 重叠率 = **召回 chunk 与证据 span 归一化后的字符级交集长度 ÷ span 长度**（固定以 span 为分母；chunk 完整包含 span 即 1.0）
- 归一化：去空白、全半角折叠、忽略脱敏掩码字符（`*`）
- **聚合覆盖判定**：Top-K 内全部召回 chunk 对同一 span 的**覆盖并集比例** ≥ 阈值即命中（默认阈值 0.5，`eval.overlap-threshold` 可配）
- 父子分片开启时按**子片**计算；覆盖并集在"Top-K 父片各自命中的全部子片"集合上算
- 多 evidence case：Hit Rate 取任一 evidence 命中；`Recall@K = Top-K 命中 evidence 数 / 总 evidence 数`
- 文档级锚定 case：命中判定为"Top-K 中出现该 doc_id 的任一分片"，不做重叠计算；其 Recall@K = 命中相关文档数 / 标注相关文档数；**报告中与 span 级 case 分组展示，不混算**

### 3.3 指标
- Recall@K、Precision@K、Hit Rate、MRR、NDCG@K；比例类指标输出 **95% 置信区间**（Wilson 区间，样本小时比正态近似稳）
- 置信区间仅作报告展示，**不参与任何判定**（需求 §4.6：门禁噪声控制由容差负责，两套机制不叠加）
- 分组输出：全体 / span 级 / 文档级 / 单轮 / 多轮（需求 §7 报告要求单轮多轮分组）

### 3.4 离线执行档（需求 §4.6）
- 评测运行时改写超时与重排超时统一放宽为 `eval.offline-timeout-ms`（默认 **10000**），线上 P95 承诺不适用
- 每条 case 记录 `degraded`（不得静默）；**降级 case 自动重试**（默认 2 次，`eval.degraded-retry`）
- 重试后仍含降级 case：run 仍标 SUCCESS 但 `case_degraded>0`，报告顶部显著提示；**M4b 不做拦截**（拦截属 M4c 门禁）
- 并发：`eval.concurrency`（默认 4）逐 case 执行，复用既有检索链路（不得复制一份检索逻辑）
- 费用护栏：提交前 `POST /api/v1/eval-datasets/{datasetId}/runs/estimate` 返回预估调用次数（嵌入/重排/改写/judge 各自次数），需求 §4.6"评测提交前展示预估调用次数与费用"

### 3.5 LLM-as-judge 规约（需求 §4.6）
- 评分维度：正确性 / 引用忠实度 / 完整性，各 1-5 分制，每档附锚定描述；固定英文 prompt 并**版本化**（常量 `JUDGE_PROMPT_VERSION`）
- `temperature=0`；judge 模型**独立配置**（`EVAL_JUDGE_MODEL`，默认取 ChatProvider 模型但可覆盖），run 记录 `judge_model` 与 `judge_prompt_version`
- 仅相同 judge 配置的 run 之间允许分数对比，`compare` 端点对不同 judge 配置标注"不可比"
- judge 分**不参与门禁**（M4c 门禁只用检索指标）
- judge 需要 expected_answer 与生成答案：M4b 无 chat 生成端点（属 M4c），**judge 本期仅在 case 有 `expected_answer` 时对"召回内容能否支撑该答案"打分**，prompt 明确这一语义

## 4. LLM 语义切分策略（需求 §4.3 第 7 种）
- `SplitStrategy` 枚举增 `LLM_SEMANTIC`；实现类走 ChatProvider
- **只输出切割点**：prompt 要求返回句子/行号数组，原文由代码按位置切，内容零改写（需求 §4.3 忠实性约束）
- `temperature=0`；切分结果**落库缓存**，缓存 key 含 `content_hash + 切分模型标识 + 提示词版本`（需求 v1.6/M2 已定），命中不重复付费
- 超长文档滑动窗口分批判定，窗口间重叠区对齐切点
- **输出强校验**（需求 §4.4 Prompt 注入防护②）：切割点必须为窗口内合法句/行号，否则该窗口降级为按长度切分并记 error 日志
- 顺带产出每个 chunk 的标题/摘要/关键词写入 `metadata`（需求 §4.3）
- 未配置 chat 模型时该策略不可选（配置校验 fast-fail，`INVALID_PARAM` 明确提示）
- 缓存表**不新增**：复用 `t_kb_system_config` 不合适，改为在 MinIO 存 `kb/{kbId}/doc/{docId}/{versionId}/split-cache/{cacheKey}.json`，与 `parsed.json` 同一存储层，天然随文档版本清理

## 5. kb-rag-web 增量（评测中心）
新增顶级菜单**评测中心**，四个 tab：
1. **评测集管理**：列表（名称/case 数/revision/最近 run 摘要）、新建、删除、"导入 Demo 评测集"按钮
2. **标注工作台**：case 列表（query/锚定类型 Tag/status Tag/证据条数/单轮或多轮 Tag）、新增 case 表单（query、可选多轮 messages 编辑器、锚定类型选择、证据编辑：选文档 + 粘贴/摘录 span）、编辑、删除
3. **证据复核工作台**：待复核 case 列表 + 每条展示失配证据原文与当前版本的 Top3 候选原文，操作"用候选替换"（REANCHOR）或"废弃 case"（DEPRECATE）
4. **评测任务与报告**：新建 run（K 值、配置矩阵多选 BM25/向量/混合/混合+重排、可选 judge 开关、提交前显示 estimate 预估调用次数）、run 列表与状态、报告页（指标对比表按配置横向排列 + 分组切换全体/span级/文档级/单轮/多轮 + 每条 case 命中明细下钻 + 降级 case 数与待复核数显著提示 + 导出 CSV）
- **检索调试页**新增"收进评测集"按钮：勾选结果卡片后选择目标评测集提交（走 `cases/from-retrieval`），图片类结果自动建文档级锚定
- **知识库详情的版本切换确认框**：`affected_eval_case_count` 不再恒为 0，需在确认文案中体现"将有 N 条评测 case 进入待复核"
- 类型层：EvalDataset/EvalCase/AnchorType/CaseStatus/EvalRun/RunStatus/EvalMode/EvalMetrics/EvalResult/JudgeConfig 等，枚举展示走 `metaOf`

## 6. kb-rag-deploy 增量（主会话自行完成，代理不需处理）
OpenAPI 同步、`.env.example` 增 `EVAL_JUDGE_MODEL`/`EVAL_OFFLINE_TIMEOUT_MS`/`EVAL_CONCURRENCY`/`EVAL_OVERLAP_THRESHOLD`、CHANGELOG、需求文档回补（若有偏离）。

## 7. 新增单测（必须）
重叠率算法（span 为分母/完整包含=1.0/归一化去空白与全半角/掩码字符忽略）、聚合覆盖并集判定（多 chunk 拼合覆盖同一 span）、父子分片下按子片计算、多 evidence 的 Hit Rate 与 Recall@K、文档级锚定命中判定、Wilson 置信区间、MRR/NDCG 计算、mode→检索参数映射（四种）、零 Key 下向量类 mode 直接 FAILED、离线档超时覆盖生效、降级 case 重试次数、dataset_revision 递增触发点（增/改/删 case）、corpus_fingerprint 组成、compare 端点的可比性判定、LLM 切分点非法时降级为按长度切分、切分缓存 key 组成、Demo 评测集导入的 content_hash 匹配与跳过、`affected_eval_case_count` 统计与版本切换时 case 置 evidence_stale

## 8. 验收清单（实现完成后主会话执行）
1. 建评测集 → 新增 3 条 span 级 case（含一条多 evidence、一条多轮）→ dataset_revision 正确递增
2. 从检索调试页勾选结果一键收进评测集 → case 的 span 与 doc_id 正确、source=DEBUG_PAGE
3. 跑 run（零 Key 环境用 BM25_ONLY）→ 报告出指标、命中明细可下钻、Recall@K 与 Hit Rate 数值可人工复算核对
4. 提交 estimate → 返回预估调用次数
5. 文档升新版本改动被标注的段落 → `activate-impact` 的 `affected_eval_case_count` 非 0、切换后对应 case 置 EVIDENCE_STALE
6. 证据复核工作台列出该 case 与 Top3 候选 → REANCHOR 后置回 ACTIVE、再跑 run 该 case 恢复命中
7. 文档级锚定 case（用图片文档）→ 命中判定按 doc_id、报告中与 span 级分组展示
8. 导入 Demo 评测集 → 10 条 case 按 content_hash 关联到 Demo 库文档，重复导入幂等
9. compare 两个 run（同 dataset_revision）→ 指标并排；人为改 case 后再跑 → compare 标 comparable=false
10. LLM 语义切分：未配置 chat 模型时选择该策略 → fast-fail 明确提示（配 Key 后的真实切分留待 Key 恢复）

> **验收环境限制（务必如实记录）**：用户 DASHSCOPE_API_KEY 当前失效。因此**向量类 mode、rerank、Query 改写、LLM-as-judge、LLM 语义切分的真实链路均无法验收**；本期验收以 BM25_ONLY 配置 + 零 Key 降级语义 + 单测覆盖算法正确性为准，Key 恢复后需补跑验收 3（四配置对比）与 10（真实切分）。

## 9. 实现期修订（主会话审查与 E2E 验收后回补，与代码一致）

### 9.1 接受的实现偏离（server 代理申报，主会话裁决通过）
1. `RetrievalCommand` 增 `bm25RouteEnabled/vectorRouteEnabled`：四种 mode 需要强制关路能力，否则 Key 恢复后 BM25_ONLY 会退化成 HYBRID，四配置对比失去意义
2. 新增 `OfflineExecutionContext`（ThreadLocal）：评测调用读离线超时并跳过生产降级监控，避免批量评测污染线上告警窗口
3. `UpdateIndexConfigRequest` 补 `split_strategy` 写入口：此前该接口从未开放切分策略写入，LLM_SEMANTIC 无法触达，属必须补的缺口
4. 父子分片 + LLM_SEMANTIC 组合本期不支持（刻意收窄；FixedLengthTextSplitter 标 @Primary 解决多 Bean 歧义）
5. judge 用独立 `judgeChatProvider` Bean（可与改写模型独立配置、强制 temperature=0）
6. Precision/MRR/NDCG 的逐项"相关"定义：该 rank 单元与任一 evidence 有正重叠（或 doc_id 命中）；MRR 复用聚合命中的 hit_rank，不另造第二套排名口径
7. Recall@K 的 CI 为证据级合并二项近似（Hit Rate 的 CI 精确）；CI 仅展示不参与判定，可接受
8. `/retrieval-feedback` 对 GOOD/BAD 均只记日志不落库（payload 无 dataset_id 无法安全落地；GOOD 的落地路径是"收进评测集"端点）

### 9.2 E2E 验收中发现并修复的两个缺陷
1. **ChatMessage 无法反序列化**：final 字段 + 仅 lombok @AllArgsConstructor，能写不能读——M2 造它时只用于序列化，M4b 评测运行第一次读回多轮 case 即失败（run 直接 FAILED）。已加 @JsonCreator 构造器
2. **split_strategy 原样存库不校验**：枚举 code 为小写（llm_semantic），接口把大写/任意字符串原样写入，既绕过"零 Key 不可选 LLM_SEMANTIC"的校验、又会在切分路由处静默失效。已在 service 单点归一化 + 非法值 INVALID_PARAM

### 9.3 验收结果（零 Key 域）
Demo 评测集导入 10/10 且幂等；BM25_ONLY run 10 case 全跑通，五个分组指标齐全、Wilson CI 方向正确；estimate 返回四类调用数；results 下钻 hit 为 boolean、hit_rank 正常序列化；compare 同 revision 可比；零 Key 下 VECTOR_ONLY 置 FAILED 并明确说明原因；LLM_SEMANTIC 与非法策略名均被 INVALID_PARAM 拦截。**受 Key 失效限制未验**：向量类三种 mode 的真实指标、rerank/改写参与的评测、LLM-as-judge、LLM 语义切分真实切分——Key 恢复后补验。
