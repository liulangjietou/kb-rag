# M13 开发契约（运维可观测：Prometheus 业务指标暴露 · 增量于 M1-M12 契约）

> 需求依据：知识库需求文档 §5 运维性要求（可监控、可告警）。M4c/M8 已有的告警体系（AlertService/TaskFailureTracker）解决"出事推 webhook"，本期补齐它的前半段——**业务指标以 Prometheus 文本格式持续暴露**，让运营者接 Grafana 看趋势、配阈值，而不是只在越线瞬间收到一条消息。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、不主动 commit）。

## 0. 范围与边界

- **本期做**：①激活既有 actuator 的 `/actuator/prometheus` 端点（`management.endpoints.web.exposure.include` 早已含 prometheus，缺的只是 `micrometer-registry-prometheus` 依赖）；②新增业务指标门面 `KbMetrics`（kb-app），在既有横切汇聚点埋计数器/计时器；③任务积压 gauge。JVM/HTTP 等基础指标由 actuator 自动配置免费提供，不重造。
- **本期不做**：Grafana 面板与告警规则文件（部署侧资产，属运维文档范畴）；指标持久化（Prometheus 拉走即可）；per-KB / per-key 维度标签（kb_id、key_id 是无界基数，Prometheus 反模式——需要按库统计时用 M10 洞察报表，按 key 统计时用 M4c 审计统计，各有专门端点）；`@Timed` AOP（项目横切惯例是显式调用，不引 AOP）。
- **埋点原则（与 M10 洞察同一哲学）**：只在**既有的横切汇聚点**搭车，不进 `RetrievalService` 内部——评测运行与发布卡点复用检索管线，不能污染在线指标。指标失败绝不影响业务路径（micrometer 计数本身无 IO，不会失败；gauge 的 DB 查询包 try/catch）。
- **M13 交付时的兼容红线**：纯新增，无表变更、无端点行为变化、无新环境变量；当时 `/actuator/prometheus` 与 health 同端口且无鉴权。v1.21 已将二者迁至默认只绑定 `127.0.0.1:20003` 的独立管理监听器，并隐藏 health 组件详情，现行部署边界见 `ACTUATOR-SECURITY.md`。

## 1. 依赖（版本一律由 Spring Boot BOM 管理）

| 模块 | 新增依赖 | 理由 |
|---|---|---|
| kb-app | `io.micrometer:micrometer-core` | KbMetrics 门面在 kb-app（埋点都在 app 层服务），kb-app 现依赖 spring-boot-starter 不含 micrometer |
| kb-api | `io.micrometer:micrometer-registry-prometheus`（runtime） | actuator 自动配置发现 registry 后即暴露 `/actuator/prometheus` |

## 2. 指标清单（命名 micrometer 点分风格，Prometheus 侧自动转下划线）

| 指标 | 类型 | 标签 | 埋点位置（既有汇聚点） |
|---|---|---|---|
| `kb.search` | Timer | `source`（console/open_api）、`zero_hit`（true/false）、`degraded`（true/false） | 与 M10 洞察相同的两个 API 边界：SearchController（console）、KnowledgeApiService 三个开放流程的洞察点（open_api，计时为检索阶段，不含生成） |
| `kb.task.completed` | Counter | `type`（TaskType 小写）、`status`（success/failed） | TaskFailureTracker.recordSuccess/recordFailure——索引管线成败的既有唯一漏斗 |
| `kb.task.backlog` | Gauge | `status`（pending/running） | TaskBacklogMetrics 按需 selectCount t_kb_task（idx_status 索引，抓取周期级频率，代价可忽略；DB 异常返回 NaN 不抛） |
| `kb.openapi.rejected` | Counter | `error_code`（ErrorCode 名，有界枚举） | ApiKeyAuthFilter.writeError——401/429 的既有唯一出口（审计因无 key id 不记 401，指标无此约束，两类都计） |
| `kb.websource.sync` | Counter | `status`（WebSourceFetchStatus 小写四态） | WebSourceService.record——四态结果的既有唯一落点 |

- 上传量不单独设指标：每次成功上传必然产生 PARSE/INDEX 任务，`kb.task.completed` 已覆盖；重复上传（planner 判重）无任务但也无新内容，不是容量信号。
- preview（管理台问答调试）与洞察同口径**不计入** `kb.search`：管理流量不进外部调用量统计。

## 3. 实现形态

### 3.1 KbMetrics（kb-app 新包 metrics）
- 构造注入 `MeterRegistry`，方法即指标语义：`recordSearch(source, latencyMs, resultCount, degraded)`、`recordTaskCompleted(taskType, success)`、`recordOpenApiRejected(errorCode)`、`recordWebSourceSync(status)`。
- 标签值全部小写、来自有界枚举或布尔，杜绝无界基数；调用方传 null 枚举时静默丢弃该样本（防御性，不抛）。

### 3.2 TaskBacklogMetrics（kb-app 包 metrics）
- 构造注入 `MeterRegistry` + `KbTaskMapper`，构造期注册 PENDING/RUNNING 两支 gauge；查询失败 error 日志带错误码并返回 NaN（Prometheus 语义上等于本轮无数据，绝不让抓取拖垮应用）。

### 3.3 埋点接线（每处一行调用，零逻辑改动）
- SearchController：检索调用前后取毫秒差，`recordSearch(CONSOLE, ...)`；
- KnowledgeApiService：`insight(...)` 私有方法增加 startedAt 入参，洞察写入旁 `recordSearch(OPEN_API, ...)`（三个开放流程共用此点，天然排除 preview）；
- TaskFailureTracker / ApiKeyAuthFilter / WebSourceService：各自的既有唯一漏斗处一行计数。

## 4. 单测（必须，精确断言；registry 用 SimpleMeterRegistry，不起容器）

- KbMetrics：每个 record 方法计一次后 registry 内 counter/timer 值与标签精确匹配；zero_hit/degraded 布尔映射；null 枚举不抛不计。
- TaskBacklogMetrics：mock KbTaskMapper 返回计数 → gauge 值匹配；mapper 抛异常 → gauge 为 NaN 不抛。
- 既有测试适配：AlertServiceTest（TaskFailureTracker 构造）、WebSourceServiceTest、KnowledgeApiServiceTest 增注入并断言关键路径计数被触发。

## 5. kb-rag-deploy（收尾）

- 无新环境变量（.env.example 不动）；OpenAPI 不涉及业务端点（actuator 面不进 kb-server.yaml）。
- CHANGELOG：新增 M13 条目，说明 `/actuator/prometheus` 自本版起可抓取及指标清单。

## 6. 验收

1. 启动 kb-rag-server → `curl 127.0.0.1:20003/actuator/prometheus` 可见 `kb_search_seconds_*`、`kb_task_backlog` 等指标及 JVM/HTTP 基础指标（M13 初版端口为 20000，v1.21 后为独立管理端口）
2. 管理台执行一次检索 → `kb_search_seconds_count{source="console"}` 递增；零命中查询 → `zero_hit="true"` 系列递增
3. 上传一篇文档索引完成 → `kb_task_completed_total{type="index",status="success"}` 递增
4. 用错误 API Key 调开放接口 → `kb_openapi_rejected_total{error_code="INVALID_API_KEY"}` 递增
5. 网页登记同步一次 → `kb_websource_sync_total` 对应四态递增
6. `mvn -B -ntp verify` 全绿
