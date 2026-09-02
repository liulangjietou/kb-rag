# M24 开发契约：模型 Token 成本台账与租户月度配额

> 状态：已实现并与代码、控制台和 OpenAPI 对齐（2026-08-14）。

## 0. 目标与边界

M24 回答两个生产问题：一次模型调用应归属哪个租户、一个租户还能否继续消费。计量放在
`kb-infrastructure` 的 Provider 出站边界，因为只有这里能覆盖嵌入、重排、对话、Judge、图谱抽取、
视觉与多模态嵌入的全部真实付费请求；业务服务各自记账会必然漏掉新增调用点。

本期不做供应商账单对账、充值/扣款、汇率换算、日配额、模型路由或自动抓取官方价格。价格由平台运维
显式维护；没有价格仍记 Token 台账，只标记为“未定价”。`monthly_token_quota=0` 表示不限制，避免升级后
把存量租户锁死。

## 1. 不变量

1. **先预占、后调用**：模型 HTTP 请求发出前，用一条条件 `UPDATE` 原子判断
   `used_tokens + reserved_tokens + 本次预占 <= monthly_token_quota`。禁止先 `SUM` 再写入，后者在并发下会
   让多个请求同时越过同一个余额。

   > **预占语句顺序（M24 后修复补齐）**：这条 `UPDATE` 必须是预占事务里对
   > `t_kb_model_usage_monthly` 的**第一条**语句，计数器行只在 `UPDATE` 影响 0 行、且经不加锁的
   > 存在性查询确认该行确实缺席时才补建。原实现每次调用先 `INSERT IGNORE` 建行再 `UPDATE`：行已存在时
   > `INSERT IGNORE` 会为判定唯一键冲突而对该行加 S 锁，随后的 `UPDATE` 需要把它升级为 X 锁——两个
   > 并发预占各持 S 各等 X，InnoDB 判定死锁并杀掉其中一个。同一文档的嵌入分批是并发的且计费到同一个
   > 租户月，因此大文档必然触发。超配额时同样不得回落到 `INSERT IGNORE`：超配额会持续到月末，那会让
   > 同一把 S 锁回到每一次被拒调用上。

2. **真实用量替换预占**：供应商响应带 usage 时，以其 input/output/total 结算；兼容 OpenAI
   `prompt_tokens/completion_tokens` 与 DashScope `input_tokens/output_tokens` 命名。
3. **未知用量保守结算**：供应商没有 usage 时按预占量结算，`estimated=1`。进程崩溃遗留的 RESERVED
   行同样保守结算，因为无法证明进程是在请求发出前还是供应商受理后死亡；直接释放会漏记真实消费。
4. **只有明确的上游调用失败才释放**：网络调用抛错时同步释放。供应商已经返回后，本地响应解析失败
   也不得释放，因为费用已经可能发生。
5. **计量不存客户内容**：台账不保存 prompt、回答、图片、凭据或异常正文；只存租户、来源、安全业务
   标识、模型维度、Token、价格快照、状态和错误类型。
6. **币种不可相加**：汇总始终按 ISO 4217 币种分别返回，不提供跨币种总额。

## 2. 预占上界

- 文本：UTF-8 字节数作为 BPE Token 的保守上界；对话额外加入消息结构开销与完整 `max_tokens` 输出预算。
- 图片：取压缩字节数与解码宽×高像素数的较大值；只读取图片元数据，不解码整张位图。不能只用压缩体积，
  因为大面积纯色 PNG 可以很小但仍占用大量视觉 patch。
- 所有算术使用饱和或精确运算，拒绝 long 溢出后把巨大调用变成负数或小数。

预占只承担并发准入，不是账单值；供应商真实 usage 到达后会用真实值替换。若供应商返回值高于预占，
仍按真实值结算并在下次调用时体现剩余额度。

## 3. 数据模型与价格单位

Flyway `V24__model_usage_and_tenant_quota.sql`：

| 表 / 字段 | 职责 |
|---|---|
| `t_kb_tenant.monthly_token_quota` | 租户每月 Token 配额；0=不限 |
| `t_kb_model_usage_monthly` | 每租户每自然月一行的 `used_tokens` / `reserved_tokens` 并发计数器 |
| `t_kb_model_usage` | 一次 Provider 调用一行的追加式安全台账与价格快照 |
| `t_kb_model_price` | `provider + capability + model` 唯一价格配置 |

月份按 `Asia/Shanghai` 自然月。价格字段是“每百万 Token 价格”，存储单位为该币种的微单位
（`1 currency = 1,000,000 micros`）。例如每百万输入 Token 为 CNY 2.00，应填 `2,000,000`。
一次调用的价格会快照到台账；之后改价只影响新调用，历史成本不会漂移。供应商仅给 total、不给输入输出时，
按输入/输出单价中的较高者保守估算。

`provider` 取各模型能力实际配置的供应商标识（去空白并转小写），不是 Java 适配器类名；因此把
OpenAI 兼容端点切到 Azure、Ollama 或 vLLM 后，可以分别维护价格而不会误记到 DashScope。

## 4. 租户归属与异步传播

`ModelUsageContext` 是计量上下文，不是权限上下文：

| 入口 | `source` | `source_id` |
|---|---|---|
| 管理台 JWT | `CONSOLE` | 用户业务 ID |
| 知识库 API Key | `KNOWLEDGE_API` | Key 业务 ID |
| Memory Key | `MEMORY_API` | Memory Key 业务 ID |
| 夜间同步 / 索引补偿 | `SCHEDULED` | source / 物理索引等安全标识 |

所有 Spring Executor 的 `TaskDecorator` 同时传播 request_id 与计量上下文，并在任务结束后恢复线程原值；
CallerRuns 回压场景也不能误清提交者上下文。图谱抽取和图片处理内部自行创建的线程池显式包装上下文。
网页/外部数据源夜间同步、索引补偿从知识库事实源解析租户后再调用模型。

## 5. 接口、权限与控制台

全部管理接口要求平台级 `tenant:manage`，普通租户无权跨租户读台账或改全局价格：

- `PUT /api/v1/tenants/{tenantId}/model-quota`：更新月配额。
- `GET /api/v1/model-usage/summary?tenant_id=&month=YYYY-MM`：配额、已用、在途、剩余、估算/未定价数与分币种成本。
- `GET /api/v1/model-usage/records?...`：安全维度明细，分页 size 强制收敛到 1–200。
- `GET/PUT /api/v1/model-usage/prices`：价格列表与幂等 upsert。

租户管理页提供“Token 配额”“用量台账”“模型价格”入口。成本按 micros 精确传输，前端只负责展示，
不重新计算财务口径。配额拒绝统一返回 HTTP 429 / `MODEL_QUOTA_EXCEEDED`，且模型请求尚未发出。

## 6. 崩溃恢复与多实例性质

`MODEL_USAGE_RESERVATION_TIMEOUT_MINUTES` 必须高于最长模型读取预算。定时恢复按
`MODEL_USAGE_RECONCILE_BATCH_SIZE` 扫描超时 RESERVED 行，保守结算为 estimated；台账行的乐观锁保证
多实例同时扫描时只有一个实例能结算并变更月计数器。本恢复器是对耐久行的幂等修复，不等同于引入通用
持久化任务调度平台。

## 7. 测试与验收

单元测试覆盖：原子配额拒绝、预占热路径不建行与首次调用补建后重试（M24 后修复）、价格快照、真实/未知用量结算、崩溃保守结算、OpenAI/DashScope usage
解析、供应商返回后解析失败不释放、UTF-8/输出预算、压缩图片像素上界、Token 加法溢出、异步上下文传播、
租户配额更新和索引补偿租户归属。管理台执行 test/lint/build；OpenAPI、Flyway、配置与两份需求文档进入
部署契约校验；里程碑结束执行整个仓库的全量单元测试门禁。
