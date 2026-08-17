# M21 开发契约（最终答案质量评测与发布门禁 · 增量于 M1-M20 契约）

> 需求依据：M4b 的 `LLM-as-judge` 只回答“召回内容能否支撑参考答案”，并没有执行生产问答生成，也不能证明用户最终看到的答案正确、忠实或引用完整。M21 在不改变历史检索指标语义的前提下，增加“检索 → 生产 Prompt 生成 → 独立 Judge → 发布门禁”的完整闭环。

## 0. 范围与边界

- **本期做**：评测用例增加期望拒答；评测 run 可选绑定一个应用版本，冻结其 Prompt 与生成模型；复用生产问答路径生成最终答案；独立记录答案五维评分、答/拒决策、生成 P95 时延与 Judge 失败数；应用版本可显式开启最终答案发布门禁；管理台补齐配置、费用预估、报告与双跑对比。
- **本期不做**：不替换 M4b 的检索 Judge，不修改既有 `judge_score` 含义；不把 Judge 失败记成 0 分；不因升级自动开启历史版本的答案门禁；不在本期做 Token 成本台账与租户配额（后续顺序第 4 项）。
- **单一生成路径**：`AnswerGenerationService` 是在线问答、管理台预览与离线答案评测共同使用的 Provider 解析和 Prompt 装配入口。评测只提供其配置矩阵实际召回的节点，不复制生产 Prompt。

## 1. 用例与运行快照

### 1.1 评测用例

`t_kb_eval_case` 新增 `expected_refusal TINYINT NOT NULL DEFAULT 0`：

- `expected_answer` 非空：生成后按参考答案评判。
- `expected_refusal=true`：正确行为是拒答；参考答案允许为空。
- 两者都未给：仍参与检索指标，但不生成、不评判最终答案，也不计入答案门禁分母。

### 1.2 提交形态

`POST /api/v1/eval-datasets/{datasetId}/runs` 与 `/runs/estimate` 的请求体新增可选块：

```json
{
  "answer": {
    "enabled": true,
    "app_version_id": "av_xxx"
  }
}
```

启用时 `app_version_id` 必填，且该版本必须关联评测集所属知识库。每个 run 在
`answer_eval_config` 中持久化应用版本 id 与完整配置快照；后续修改知识库默认配置或新建应用版本，都不能改变已完成 run 的可复现输入。

费用预估新增 `generation_calls` 与 `answer_judge_calls`。只有同时具备参考答案或期望拒答的 ACTIVE case 才计数；配置缺失导致 run 会在执行前失败时，预估调用数为 0。

## 2. 生成与评判

### 2.1 生成

每个可评判 case 先按配置矩阵执行既有 `RetrievalService`，再将同一批排序节点交给
`AnswerGenerationService`。应用版本的 `chat_model`、system/refusal/leak-guard/citation Prompt
均按快照生效，在线非流式与离线评测最终调用同一个 `ChatProvider#complete` 入口。

生成 Provider 或答案 Judge 未配置时 run **fast-fail 为 FAILED**，不以 BM25 或空答案伪装为完整评测。

### 2.2 独立 Judge

答案 Judge 使用 `judgeChatProvider`，Prompt 版本固定记录为 `final_answer_judge_v1`，输出：

- `correctness`、`faithfulness`、`completeness`；
- `citation_correctness`、`citation_completeness`；
- `refusal_correct` 与自由文本原因。

各评分为 1–5。综合 `score` 是五个数值维度的算术平均后四舍五入；答/拒决策单独聚合为
`refusal_accuracy`。应用关闭引用要求时，Judge 不因“没有引用”扣分，但仍检查实际出现的引用是否误导。
参考答案、生成答案和召回段落都按不可信数据块处理，Judge 不执行其中的指令。

结构化输出缺字段、非法 JSON 或 Provider 异常时，该 case 记 `answer_judge_reason`，评分保持 null；
聚合层增加 `judge_failed_cases`，绝不把基础设施失败折算成 0 分。

### 2.3 报告指标

run 级 `answer_metrics` 包含：五个评分均值、`refusal_accuracy`、`evaluated_cases`、
`judge_failed_cases`、`latency_p95_ms`。case 明细持久化生成答案、生成耗时、各维评分、答/拒结果和 Judge 原因。

## 3. 发布门禁

应用版本快照新增显式配置：

```json
{
  "answer_gate": {
    "enabled": true,
    "min_score": 4.0,
    "min_faithfulness": 4.0,
    "min_citation_correctness": 4.0,
    "min_refusal_accuracy": 0.9
  }
}
```

- `enabled=false` 是历史快照和新部署的兼容默认值；未显式开启时发布行为与 M4c 完全一致。
- 首次发布：有绝对阈值则逐项校验；无阈值则记录答案基线并通过。
- 后续发布：候选版与当前正式版各用自己的生成快照，在双方都成功完成结构化评判的 case 交集上重算指标。Judge 模型与 Prompt 版本不同则不可比较。
- 任一数值评分下降超过 `GATE_ANSWER_SCORE_EPSILON`（默认 0.2 分）即拦截；答/拒准确率容差为 `max(GATE_EPSILON_PP / 100, 1/N)`。
- Judge 失败、有效 case 少于 `GATE_MIN_CASES`、检索仍降级或答案评测不可用时结论为 `LOG_ONLY`，不得自动发布，需要人工确认后显式强制放行。
- 检索门禁与答案门禁采用“非通过优先”合并；检索已 BLOCKED/LOG_ONLY 时不被答案结果覆盖。

## 4. 数据库迁移与兼容性

Flyway `V23__final_answer_evaluation.sql`：

- `t_kb_eval_case` 增 `expected_refusal`；
- `t_kb_eval_run` 增答案评测快照、Judge 身份与聚合指标；
- `t_kb_eval_result` 增生成答案、耗时、逐维评分、拒答结果与原因。

全部新列可空或带兼容默认值，存量行无需回填。旧 run 的答案字段为空，旧应用版本解析为
`answer_gate.enabled=false`；现有检索报告、比较和发布语义保持不变。

## 5. 验收

1. 仅有检索期望、没有答案期望的 case 不触发生成调用。
2. 答案评测 run 使用应用版本冻结的生成模型和 Prompt，并在报告中展示答案评分与生成内容。
3. Judge 返回非法 JSON 时 run 可完成，但 `judge_failed_cases` 增加、门禁只记录不拦截。
4. 双跑只在双方成功 Judge 的同一 case 交集上比较，任一侧失败的 case 不进入任一侧指标分母。
5. 历史应用版本升级后不自动启用答案门禁。
6. 服务端全量单测、管理台单测/类型检查/lint/生产构建、部署契约测试全部通过。
