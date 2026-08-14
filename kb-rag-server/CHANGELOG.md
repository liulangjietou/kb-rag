# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。
标注 `[schema]` 的条目包含数据库迁移脚本，升级时会自动执行 Flyway。

## [未发布]

尚未打过 tag，以下条目全部属于首个发布版本的内容，按里程碑倒序排列。

### M23 · Confluence Cloud 数据源连接器

- M14 `ExternalConnector` SPI 新增 `source_type=confluence`：Space Key 解析、REST API v2
  cursor 分页、`pageId:version` 增量判断与 storage HTML 正文获取；页面继续复用普通上传、治理、
  版本和索引链路，无数据库迁移、配置键或第三方依赖。
- 新增连接器特定配置 fast-fail；分页 `_links.next` / Link header 均受同源约束且禁自动重定向，
  Basic API Token 不会发往其他 origin。响应体受上传大小约束，S3 对象读取同步补齐有界读取。
- 管理台外部数据源表单支持 S3/OSS 与 Confluence Cloud 两种类型，字段标签、同步范围和页面明细
  按连接器切换。完整契约见 `kb-rag-deploy/docs/M23-CONTRACTS.md`。

### M22 · MCP 2026-07-28 双协议兼容

- `McpServerEngine` 在同一端点按单次请求识别协议时代：新增现代 `server/discover`、逐请求 `_meta`、
  `resultType` / serverInfo、工具目录 TTL/cacheScope 与稳定排序；原 2025-03-26 / 2024-11-05
  initialize、工具与业务错误平面保持兼容。
- 现代 transport 严格校验 `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name`（含 Base64 sentinel）：
  头体不一致 400/-32020、版本不支持 400/-32022、方法未实现 404/-32601。
- 新增 `McpOriginValidationFilter`，在 Key 鉴权前复用 CORS 白名单阻断非法浏览器 Origin；无 Origin 的
  服务间客户端不受影响。零新增 Maven 依赖、配置键和数据库迁移。

### M21 · 最终答案质量评测与发布门禁

- `[schema]` Flyway `V23__final_answer_evaluation.sql` 为评测用例增加期望拒答，为 run 冻结应用问答配置与答案 Judge 身份，为 case 结果记录生成答案、耗时、五维评分、答/拒结果和失败原因；存量行兼容，无需回填。
- 新增 `AnswerGenerationService`，将开放问答、管理台预览和离线评测的 Provider 解析与 Prompt 装配收敛为同一路径；答案评测不再用自造 Prompt 近似生产行为。
- 新增独立的 `FinalAnswerJudgeService`、聚合器与答案门禁纯函数。Judge 失败不折算 0 分；双跑只比较双方均成功 Judge 的共同 case；历史应用版本的答案门禁默认关闭。
- 评测提交和费用预估支持绑定应用版本；发布门禁在显式开启时联合检索与答案结论，并新增 `GATE_ANSWER_SCORE_EPSILON`（默认 0.2）。完整契约见 `kb-rag-deploy/docs/M21-CONTRACTS.md`。

### 安全加固（Actuator 管理平面）

- `/actuator/health`、`/actuator/info` 与 `/actuator/prometheus` 从 `20000` 业务监听器迁至
  独立管理监听器，默认地址为 `127.0.0.1:20003`，避免管理信息随业务 API 对外暴露。
- `management.endpoint.health.show-details` 从 `always` 收敛为 `never`，探针只得到聚合状态，
  组件拓扑与错误详情改由应用日志承载。
- 新增 `MANAGEMENT_SERVER_PORT` / `MANAGEMENT_SERVER_ADDRESS` 配置契约单测；远程抓取必须
  显式开放监听地址并在防火墙或带认证的反向代理处限制来源。

### 工程修复（monorepo 配置基线）

- `kb.demo.data-dir` 的默认值由个人开发机绝对路径改为 monorepo 相对路径
  `../kb-rag-deploy/demo`，保持从 `kb-rag-server` 目录启动时仍可直接导入 Demo。
- 根级 CI 的 Java 基础门禁显式排除需要真实 Chromium 的 `browser` 标签；浏览器集成测试保留为
  预装 Playwright Chromium 后单独执行的验证项，避免环境依赖导致基础门禁不确定或长期挂起。


### 缺陷修复（M15 后修复：角色编辑抽屉不回显已有配置，角色列表跨租户不可辨识）

- **缺陷一（控制台，主诉）**：角色管理页点"编辑"，抽屉里名称、说明、数据范围与**已授权限的勾选**全部是空的——不是权限没保存，是压根没读出来。`RoleManagePage#openEdit` 先调 `form.setFieldsValue(...)` 再 `setDrawerOpen(true)`，而抽屉带 `destroyOnClose`：赋值发生的那一刻表单子树还没挂载，`useForm` 实例没有连上任何 `Form` 元素，antd 直接丢弃这次赋值。**症状看起来是"权限丢了"，实际是保存后再打开就看不到自己保存了什么**——运维要么反复重勾、要么把已有授权覆盖成空集提交上去
- 改为 `initialValues` 回填，并给 `Form` 加一个每次打开自增的 `key`。**两者是一对耦合条件，不是双保险**：rc-field-form 只在挂载那一次把 `initialValues` 写进 store（`setInitialValues(values, !mountRef.current)`），沿用同一个组件实例时只更新引用；而写进去时走的是 `merge(initialValues, store)`——store 里的残留值会赢，只有卸载时被记进 `prevWithoutPreserves` 的字段（即 `preserve={false}` 的那些）才被强制取 `initialValues`。`key` 保证重挂载、`preserve={false}` 保证残留值让位，删掉任一半都会退回"打开看到上一个角色的值"。`destroyOnClose` 通常也能触发重挂载，但要等关闭动画跑完，快速关掉再打开赶不上
- 顺带一处 UI 行为变化：编辑数据范围为"指定知识库"的角色时，切到"全部知识库"再切回来，列表恢复为打开抽屉时的原始选择（此前恢复为空、必须重选）。提交语义不变——`kb_scope_all=true` 仍强制提交空 `kb_ids`，那条注释讲的是 payload，不是 UI
- **缺陷二（同页，顺带）**：`t_kb_role` 是 `KbTenantLineHandler.OPERATOR_UNFENCED_TABLES` 的两张表之一，持 `tenant:manage` 的平台运维读它不带租户条件（M16 §1.3 的既定放行，否则新建租户没人能给它授第一个角色）；而 `TenantService#copyBuiltinRoles` 给每户照抄五个内置角色，`code` 与 `name` 全同。**隔离是对的，可辨识性是缺的**：返回体里只有 `role_id` 能区分，页面于是呈现为"每个内置角色重复 N 遍"的表，编辑哪一行全凭运气
- `RoleResponse` 补 `tenant_id`（读取 `Role#getTenantId`，该列由 `V17__tenant_doc_acl_audit.sql` 添加，**本次无 schema 变更**）；角色表格在持 `tenant:manage` 时多渲染一列"所属租户"，租户名复用 `GET /api/v1/tenants`，与用户管理页同一手法。其余账号读到的本就只有自己租户那一份，不渲染该列
- `SaveRoleRequest` 不收 `tenant_id`：普通租户由行级围栏在 `INSERT` 时补列，平台运维走放行分支、围栏不参与，落库取该列的 `DEFAULT 'tnt_default0000000'`——也正是这类账号自己所在的租户（`tenant:manage` 只发给默认租户超管）
- 单测新增 2 例（`RoleResponseTest`，kb-api 37 → 39，`mvn -o -DskipITs clean test` 全绿 1227 → 1229）：同 `code` 不同租户的两行必须靠 `tenant_id` 区分、缺失的授权与范围映射为空列表而非 null。变异验证：把 `RoleResponse.from` 里的 `role.getTenantId()` 换成 `null`，`shouldCarryTheOwningTenantSoTheOperatorCanTellRepeatedBuiltinRolesApart` 一例转红
- 前端无测试框架（`tsc -b` + `oxlint`），两处均已通过；无 Flyway、无新增配置键，**对外行为变更仅一处**：`GET /api/v1/roles` 与 `/roles/{roleId}` 返回体多一个 `tenant_id` 字段（纯新增，存量调用方不受影响）

### 安全修复（M16 后修复：按 `kbId` 寻址的列表与批量入口不解析根表）

- **缺陷**：上一条修的是"路径只带从属资源 id"那一类入口，这一条修的是另一类——路径自带 `kbId`、但链路上从头到尾没有一次对 `t_kb_knowledge_base` 的查询。这类入口的守卫只有 Controller 里那行 `AccessGuard.requireKbAccess(kbId)`，它问的是"这个库在不在你角色配的数据范围里"，而 `kb_scope_all` 对内置角色恒为真，于是**只要报一个别家的 `kbId`，后续那条按 `kb_id` 过滤的语句就照常执行**：`GET /kb/{kbId}/documents`（列出别家全部文档与解析状态）、`/trash`（别家的删除历史）、`/search-insights` 与 `/stats`（别家用户搜过什么，原始 query 文本）、`/documents/batch-delete` 与 `/batch-reindex`（批量删/重建别家文档）、`/documents/confirm`、`/rebuild` 与 `/rebuild-status`（替别家跑一遍全库重建）、`/documents/{docId}/visibility` 读写（别家文档的密级与授权角色）
- `/documents/{docId}/visibility` 那两条值得单独说：`DocumentAclService#requireOwned` 校验的是"文档挂在这个 kbId 下"，跨租户调用方把别家的 `kbId` 与该库下的 `docId` 一起传进来**完全对得上**——校验通过，密级照读照改。"从属行属于这个父"和"这个父属于你"是两个问题
- 修复落在服务层，不在 Controller：`DocumentService#list` / `#requireAllInKb`、`DocumentPreviewService#confirmAll`、`DocumentGovernanceService#listTrash`、`RebuildService#submit` / `#status`、`SearchInsightService#list` / `#stats`、`DocumentAclService#requireOwned` 首行补 `knowledgeBaseService.require(kbId)`。`requireAllInKb` 一处覆盖 batch-delete 与 batch-reindex 两个入口——它本来就是批量作用域校验的唯一落点，缺的只是第一问
- 28 处 Controller 的 `AccessGuard.requireKbAccess(kbId)` 统一换成 `kbResourceGuard.requireKb(kbId)`（7 个控制器），**判定顺序由此在全域一致**：租户 404 先于数据范围 403。原先 Graph 五端点、chat-imports、`/kb/{kbId}/search`、知识库增改删这些服务层已经有 `require` 的入口，顺序是反的（Controller 先答 403），跨租户与"范围外"在状态码上可区分
- **对外行为变更**：跨租户从 403（或成功）收敛为 404，同租户范围外仍是 403
- 单测新增 6 例：`DocumentServiceTest`（list / reindexAll / requireAllInKb 三个入口一并拒绝且从属表零语句）、`DocumentPreviewServiceTenantTest`（新建）、`DocumentGovernanceServiceTest`（回收站）、`RebuildServiceTest`（重建与状态，且不提交任何索引任务）、`SearchInsightServiceTest`（列表与统计）、`DocumentAclServiceTest`（密级读写，且不落 ACL 写操作）
- 无 schema 变更、无新增配置键与环境变量；开放 API 与定时任务链路不经这些方法，行为不变

### 安全修复（M16 后修复：`KbScopeGuard` 全族假守卫，43 个按资源自身 id 寻址的入口零租户隔离）

- **缺陷**：PR #36 删掉的 `KbScopeGuard#requireWebSourceAccess` 不是孤例，是一族里的一个。同类的另外 8 个方法（document / chunk / annotation / dataset / case / run / ext-source / feedback）逐字同构：第一行 `if (AccessGuard.unrestrictedKbScope()) return;`，随后 `AccessGuard.requireKbAccess(kbId)`——**一行租户判断都没有**。而 `V16__rbac.sql:163-169` 五个内置角色一律 `kb_scope_all=1`、`TenantService#copyBuiltinRoles` 建租户时照抄，所以第一行那个短路在真实部署里对租户 SUPER_ADMIN 与未配数据范围的 KB_ADMIN 恒成立：**这些守卫的开销为零、判定恒为放行**。站在它们后面的 43 个控制台端点因此全都是跨租户可达的（44 处调用点，`convert` 一个端点调两次），其中破坏面最大的几条：`PUT /ext-sources/{sourceId}`（覆写别家的 endpoint 与 AK/SK）、`POST /ext-sources/{sourceId}/test`（拿别家凭据向别家对象存储发外网请求）、`DELETE /ext-sources/{sourceId}`（`hardDeleteById`，不可恢复）、`/documents/{docId}/purge`（彻底清除别家文档）、`/documents/{docId}/versions/{versionId}/activate`（把别家文档回滚到旧版本并重跑索引）
- **短路吞掉的不只是数据范围判定**：`requireDatasetAccess` 查的 `t_kb_eval_dataset` 本来就在 `FENCED_TABLES` 里、围栏会自动拼租户条件——但短路让那条语句压根不执行。**一个 `return` 把已经写好的围栏一起跳过了**，这是"只覆盖数据范围的守卫比没有守卫更危险"的第二种表现形式
- 类重命名 `KbScopeGuard` → **`KbResourceGuard`**（`io.kbrag.app.auth`），9 个方法全部改成"先解析围栏根表、再判数据范围"，短路整体删除。名字本身是根因的一部分：`ScopeGuard` 诚实地描述了它当时做的事，而那件事不是隔离边界，留着这个名字下一个 review 还会误读。11 个 Controller 的调用点随之改名，编译期收口
- 解析形态按资源分两类：`t_kb_eval_dataset` 自己就是围栏根（case 与 run 经它两跳，run 走 `dataset_id` 而不是行上那个 `kb_id`——同一条链路只留一个事实源）；其余六种从属资源经 `kb_id` 反查 `t_kb_knowledge_base`。成本是每次多一条主键点查，且这条点查发生在任何写语句、内容读取、外发请求之前
- 服务层同步补齐，覆盖"不经 Controller 守卫"的所有调用方（这是 `EvalDatasetService#updateCase` 早就用对的形态）：`EvalRunService#requireRun` 补 `evalDatasetService.require(run.getDatasetId())`（原先裸 `selectOne`，与同类的 `requireCase` 相比属漏改）、`ExtSourceService#require` 与 `RetrievalFeedbackService#require` 补根表反查、`ExtSourceService#list` 与 `RetrievalFeedbackService#list`/`record` 补 `knowledgeBaseService.require(kbId)`
- 两个 Controller 的 `AccessGuard.requireKbAccess(kbId)` 换成 `kbResourceGuard.requireKb(kbId)`（`GET /kb/{kbId}/ext-sources`、`GET /kb/{kbId}/retrieval-feedback`、`POST /retrieval-feedback`）：原先那行只判数据范围，且排在服务层的租户判定之前，**顺序反了会用 403/404 的差异泄露"这个 id 在别的租户里存在"**
- **对外行为变更只有一处**：跨租户访问从 403（或成功）收敛为 404，且只影响本就不该成功的调用。同租户、数据范围外仍是 403，单测钉住这两者的顺序
- 单测新增 30 例、删除旧守卫的 8 例，`mvn test` 全绿（kb-app 711 → 733，kb-api 37 不变）：`KbResourceGuardTest` 重写（23 例）——六种从属资源各一条跨租户 404、`kb_scope_all` 不再短路、两个判定都会失败时答 404 而非 403、同租户范围外答 403、无主体线程放行、从属行不存在时不发根表查询、run 经 `dataset_id` 而非行上 `kb_id` 解析；新增 `ExtSourceTenantIsolationTest`（4 例）钉住跨租户时 `updateById`/`hardDeleteById`/`hardDeleteBySourceId`/连接器解析一条都不发出；`EvalRunServiceTest`、`RetrievalFeedbackServiceTest`、`RetrievalFeedbackOpenApiTest` 补 3 例
- **测试有效性经两组变异验证，共 14 例转红**：①把守卫的根表解析抽掉（`findFencedBase` 恒返回非空）→ `KbResourceGuardTest` **8 例**转红——document / chunk / annotation / ext-source / feedback 五种从属资源的跨租户用例 + `requireKb` + `kb_scope_all` 不短路 + 两判定皆失败答 404；dataset / case / run 三条**不受影响**，因为它们的租户解析走的是 `t_kb_eval_dataset` 自己的围栏、不经这一跳，这正是两类解析形态的分界。②把服务层三处根表反查抽掉（`ExtSourceService#require`/`#list`、`RetrievalFeedbackService#require`/`#list`/`#record`、`EvalRunService#requireRun`）→ **6 例**转红，分布在 `ExtSourceTenantIsolationTest`(3)、`RetrievalFeedbackServiceTest`(2)、`EvalRunServiceTest`(1)。两组变异均已还原
- **测试边界（诚实说明）**：与 V21/V22/PR #36 同样的限制——跨租户过滤由 MyBatis-Plus 拦截器完成，项目无集成测试基建（无 `@SpringBootTest`/Testcontainers），单测无法真正发出带围栏的 SQL。跨租户在测试里表达为"根表读作 null"，能钉住的是"每个入口都经根表解析"+"解析失败时后续语句一条不发"，围栏本身由 `KbTenantLineHandlerTest` 覆盖
- 无 schema 变更、无新增配置键与环境变量

### 修复（M4b/M4c 异步化后修复：线程池形状、拒绝后的状态自锁与 requestId 断链）

前一次修复把 `EvalRunService` / `ReleaseGateService` 的 `@Async` 自调用改成显式注入 Executor 手工 `execute`，那个修复本身是对的。但它让四条此前"看起来异步、实际同步"的路径第一次真的进队列——**永不排队就永不拒绝**，于是下面四个问题同时从理论变成现实。

- **池的 max 是个到不了的数字**。`ThreadPoolTaskExecutor` 只有队列**满**之后才扩容到 max，所以"深队列 + 更大的 max"里的 max 永远不发生。`evalTaskExecutor` `core=2/max=6/queue=50`，稳态并发恒为 2，而它上方的 javadoc 写着"一次提交最多 6 个 run、各自独立交给这个池"；`gateTaskExecutor` `core=1/max=4/queue=20` 同病。这条 `AsyncConfig` 自己在 `QUEUE_CAPACITY` 的注释里写过（索引池 `core=2,max=4` 挂 200 深队列常年只有 2），却没有落到后加的池上。现改为 `evalTaskExecutor` 6/6/50、`gateTaskExecutor` 4/4/20：**6 是一次配置矩阵的上限**，低于它会把控制台呈现为"一个动作"的提交悄悄串行化；**排队的 gate 不是晚点跑而是发布卡住**，版本整段等待期都停在 `GATING`。`auditTaskExecutor` 1/4/2000 与 `extSourceTaskExecutor` 1/2/100 是同一类谎话，但按**当前真实并发**收敛为 1/1（行为零变化，只是不再骗人）。检索池与流式池的 0 队列是刻意例外——没有队列可填，扩容到 max 是第一件发生的事
- **规则本身做成了测试**：`AsyncConfigTest` 用反射遍历 `AsyncConfig` 全部 `@Bean`，断言"要么 queue==0，要么 core==max"，并要求至少发现 10 个池（防止反射失效后空跑成绿）。这条已经踩过两次，遍历而非逐 bean 断言，是为了不出现第三次
- **被拒绝后的状态机自锁**。两处提交的 `try/catch` 都写在 lambda **内部**，`execute()` 本身没有保护，而两处都是"状态已经落库之后"才提交。评测侧：run 行已 insert 成 `PENDING`，被拒后永不执行，且同批前面几个配置的行也已落库、已在跑，成半截提交的孤儿行；门禁侧更严重，`markGating` 先执行、`submitGate` 后执行，被拒则版本永久停在 `GATING`——而 `release` 入口的守卫恰好拒绝从 `GATING` 再次发布，**自锁只能改库**。两个池都保留默认 `AbortPolicy` 不换 CallerRuns（把整条评测 run 拽回提交它的 HTTP 请求线程，恰恰是上一次修复干掉的形态），兜底改写在提交处：`EvalRunService` 把被拒的 run 就地改判 `FAILED` 并写明原因、不上抛（上抛会让同批已创建的配置无人交代）；`ReleaseGateService` 把被拒的门禁交给既有的 `failGate`，记为 `LOG_ONLY/RUN_FAILED`，与"门禁抛异常"同一个可重试出口
- **门禁 30 分钟超时预算第一次变得可触达**。修复前 `submit` 内联跑完才返回，`awaitCompletion` 首轮即见终态，超时形同虚设；现在双跑真进队列，池被占满时会排队等待。超时返回非终态 run → `succeeded=false` → 裁决 `LOG_ONLY/RUN_FAILED`：不自动发布、强制发布仍可用、版本可重试，这是正确的答案，但它是一条修复前不存在、且零覆盖的路径。补 `shouldRecordLogOnlyWhenTheDualRunOutlastsItsBudget` 钉住它，并额外断言**不读未完成 run 的 case 行**——半截写入的 per case 行不是一次比较，拿它算指标会把"没结论"变成"有信心的错数"。超时日志同时带上预算值与未完成 run 的状态
- **requestId 在 CallerRuns 上断链**。装饰器的 finally 无条件 `RequestIdHolder.clear()`（即 `MDC.remove`）。`evalCaseTaskExecutor` 与 `embedTaskExecutor` 用 CallerRuns 做回压，队列满时任务回跑在**提交者**线程上，跑完清掉的是提交者自己的 requestId——那条评测 run / 那次索引从队列填满的一刻起，后半段日志全部失去关联 id。改为记下运行前绑定的值再放回：worker 线程上本就没有绑定，恢复 null 即等于原本想做的 clear。装饰器降为包级可见以便直接单测两种交接形态（否则要填满 500 深队列才能碰到第二种）
- **新增 `EvalRunCompensationService`**（fixedDelay 5min，`kb.eval.stuck-*` 四个键）：提交时的拒绝兜底只覆盖了"没崩溃"那一种孤儿，进程中途死掉留下的 `PENDING`/`RUNNING` 行没有任何线程会再碰——控制台永远显示"评测进行中"，backlog 指标永远算它一份。扫描超过 `stuck-timeout-minutes`（默认 120）没动过的行改判 `FAILED`。两个刻意选择：**只改判不重跑**（`execute` 插 case 行前不清旧行，重跑会让 per case 行翻倍、污染包括门禁在内的所有基于它的指标）；**走 wrapper update 不走 `updateById`**（后者会 bump 乐观锁版本，把一个被早收的慢 run 自己那次写入静默吞掉，留下一堆挂在 FAILED run 下的结果行），where 里带状态谓词，选中到写入之间自己终态了的 run 原样不动
- **`kb.eval.concurrency` 的语义变更补文档**（上一次修复的静默行为变更）：它从"每个 run 的 case 并发"变成"全部在跑评测的 case **全局**并发"，默认吞吐较修复前净降约 6 倍。`application.yml` 补注释说明"六配置矩阵总共判 4 个 case、不是每配置 4 个，要提速调这个不是调 run 池"，`ARCHITECTURE.md` §3.7 线程池表补上 `evalCaseTaskExecutor` / `embedTaskExecutor` 的全局上限语义与全部池的真实 core/max/queue
- 单测：新增 `AsyncConfigTest`（3 例：池形状规则 + 装饰器两种交接形态）、`EvalRunCompensationServiceTest`（6 例）；`EvalRunServiceTest` 新增 3 例（被拒 run 改判 FAILED 且带 finishedAt、整批被拒不留半截、**每个 case 提交完才 join**）；`ReleaseGateServiceTest` 新增 2 例（门禁被拒落可重试裁决、双跑超预算）；`ApiKeyServiceTest` 补上注释里声称"asserted separately"但全仓不存在的那条断言——last-used 写入必须离开鉴权请求线程
- **`judgeAll` 的并发性此前没被钉住**：原用例是单 case + 单线程池，把 `join` 挪进提交循环照样绿（产出的行逐字节相同，输出无从分辨）。新用例用 2 个 case + 2 线程池，两个 case 互相等待对方到场才返回，串行化的提交循环会把自己等进超时并让 run 失败
- **测试有效性经变异验证**：装饰器改回无条件 clear、eval 池改回 `core=2`、去掉两处拒绝兜底、`join` 挪进提交循环、补偿扫描改用 `updateById` —— 五组变异逐一施加后，只有对应的新用例转红（共 8 例），既有用例全程不受影响
- 无 schema 变更；新增配置键 4 个（`kb.eval.stuck-scan-enabled` / `stuck-scan-interval-ms` / `stuck-timeout-minutes` / `stuck-scan-batch-size`，均带默认值，不配即生效）
### 安全修复（M4c 后修复：应用版本按 id 寻址的入口缺少租户解析）

- **缺陷**：`t_kb_app_version` 是从属表，经 `app_id` 归属租户，不带 `tenant_id` 也不在 `KbTenantLineHandler.FENCED_TABLES` 里——这个设计没问题，问题是 `AppVersionService#require` 是 `t_kb_app_version` 上的裸 `selectOne`，从不解析根表 `t_kb_app`（`t_kb_app` 在围栏名单内，解析它即可获得租户围栏），围栏在那条语句上什么都没做。`/api/v1/app-versions/{appVersionId}` 的五个端点——详情、绑定门禁评测集、提交测试、发布、回滚——全部只有 `@RequiresPermission` 功能权限码，没有任何租户或数据范围守卫。后果：任意租户持 `app:release` 的账号凭一个 `appVersionId` 就能**发布 / 回滚别家租户的应用版本**（直接改变别人对外 API 被服务的内容），持 `app:read` 就能读它的配置快照——含关联的 `kbIds` 与模型配置。与 V21 记忆库、V22 站点凭据、网页源是同一类缺陷的第四处
- **发布是其中最贵的一个入口**：`ReleaseGateService#release` 不只是切状态，它会在 `GATE_EXECUTOR` 上启动同语料双跑，对别家租户的知识库发起真实检索与模型调用（嵌入 / rerank / 生成），并在通过后冻结索引快照。修复后这条链在第一跳就断，`markGating` / `evalRunService.submit` / `releaseSnapshotService.freeze` / `promote` 一个都不发生
- 新增 `AppVersionGuard`（独立 bean，`io.kbrag.app.appcenter`），形态与 `WebSourceGuard` / `MemoryLibraryGuard` 同构：先按 `app_version_id` 定位（这一跳物理上无法避免，该列只存在于从属表，且只读、不改状态），再用 `AppService#find` 解析根表 `t_kb_app`，跨租户在那里读作"不存在"。`AppService` 相应新增 `find`（返回 null 的形态，`require` 改为委托它），因为守卫需要自己措辞
- **守卫落在 `AppVersionService#require` 背后而不是各入口前面**，这是本次的关键取舍：这个方法被本服务自调用 5 处（`setGateDataset` / `submitTest` / `promote` / `rollback`）、被 `ReleaseGateService` 调用 5 处（`release` / `promoteWithSnapshot` / `runGate` / `failGate`）、被 `KnowledgeApiService#previewVersion` 调用 1 处。放在入口处必然漏，放在这里则所有入口一致生效，且新入口自动继承
- **跨租户与不存在必须是同一个回答**：两者都是 `VERSION_NOT_FOUND` + 同一句 `application version not found`。第二跳失败若报成 `APP_NOT_FOUND`，等于用错误码差异告诉调用方"你猜的这个 id 是真的、只是在别人那里"，404 文案也因此不带 `appId`
- **判定顺序：租户（404）先于数据范围（403）**。`gate-dataset` 是本域唯一携带第二个资源的入口，原先 `AppVersionController` 先校验入参 `datasetId` 的数据范围、再进服务解析版本——跨租户的版本会先撞上评测集的 403。`kbScopeGuard.requireDatasetAccess` 因此从 Controller 移入 `AppVersionService#setGateDataset`，排在 `require` 之后；Controller 只剩参数传递，`KbScopeGuard` 依赖一并删除
- **开放 API 与后台线程零影响**：对外 `search` / `chat` 走 `resolveForCall(appId, versionLiteral)`、不经 `require`，其 `appId` 由 `ApiKeyPrincipal#requireAccessTo` 的 Key 绑定授权范围把关，本次一行都没动。`GATE_EXECUTOR` 与预览流执行器上没有控制台主体，`ignoreTable` 整条跳过围栏、行为不变——那两条线程只会看到已被请求线程守住的 `appVersionId`，这是 M16 对后台线程的既有语义
- 单测：`AppVersionServiceTest` 新增 3 例（真实 `AppVersionGuard` + mock `AppService`，跨租户表达为"根表读作 null"，与围栏在控制台线程上的真实行为一致）——跨租户的 `require`/`submitTest`/`setGateDataset`/`promote`/`rollback` 全数 404 且无 `updateById`、无评测集读取；跨租户与不存在的错误码和文案逐字相同且不含 `appId`；租户判定先于数据范围（同租户范围外 403、跨租户 404 且 `requireDatasetAccess` 根本没被调用）。`ReleaseGateServiceTest` 新增 1 例钉住发布链路：版本解析失败时不 `markGating`、不提交双跑、不冻快照、不 `promote`
- **测试有效性经变异验证**：把守卫的根表解析短路掉后，`AppVersionServiceTest` 的 3 个新用例全部转红且 26 个既有用例不受影响。`ReleaseGateServiceTest` 那 1 例在变异下仍绿是符合预期的——它 mock 掉了 `AppVersionService`，钉的是"解析失败之后这条链什么都不做"，守卫本身由前 3 例覆盖
- **测试边界（诚实说明）**：与 V21/V22 同样的限制——跨租户过滤由 MyBatis-Plus 拦截器完成，项目无集成测试基建（无 `@SpringBootTest`/Testcontainers），单测无法真正发出带围栏的 SQL。能钉住的是"每个入口都经根表解析"+"解析失败时后续语句一条不发"，围栏本身的行为由 `KbTenantLineHandlerTest` 覆盖
- 无 schema 变更、无新增配置键与环境变量；版本状态机、门禁三态、快照冻结与回滚语义均不变

### 安全修复（M12/M17/M18 后修复：网页源按 id 寻址的入口缺少租户解析）

- **缺陷**：`t_kb_web_source` 是从属表，经 `kb_id` 归属租户，不带 `tenant_id` 也不在 `KbTenantLineHandler.FENCED_TABLES` 里——这个设计没问题，问题是按 `source_id` / `kb_id` 直接寻址的四个入口压根不查根表 `t_kb_knowledge_base`，围栏在那几条语句上什么都没做。任何租户凭一个 `sourceId` 就能：触发别家网页源的抓取（`POST /web-sources/{sourceId}/sync`，还会连带走一遍文档上传管线往别家知识库写版本）、改它的 `sync_enabled` / `render_js` 开关（`PUT /web-sources/{sourceId}`）、**硬删**它的登记（`DELETE /web-sources/{sourceId}` → `hardDeleteById`，不可恢复）；凭一个 `kbId` 就能列出别家知识库登记的全部 URL 与同步状态（`GET /kb/{kbId}/web-sources`）。与 V21 记忆库、V22 站点凭据是同一类缺陷的第三处，本次补齐
- **原先站在这四个入口前面的守卫是假的**，这是根因而不是细节：`KbScopeGuard#requireWebSourceAccess` 第一行就是 `if (AccessGuard.unrestrictedKbScope()) return;`，而 `kbScopeAll` 对租户的 SUPER_ADMIN、未配数据范围的 KB_ADMIN 都成立（常见配置）——这类账号连 `requireOwner` 都走不到；即便走到，`AccessGuard.requireKbAccess(kbId)` 校验的也只是"这个库在不在调用者角色配的数据范围里"，**从头到尾没有一处比对租户**。一个只覆盖数据范围的守卫比没有守卫更危险：它让 review 以为这条路径已经守住了。该方法与 `KbScopeGuard` 的 `WebSourceMapper` 依赖一并删除，避免被再次误用；类注释补上"这里检查的是数据范围、永远不是租户"的边界说明
- 新增 `WebSourceGuard`（独立 bean，`io.kbrag.app.websource`），网页导入四个入口一律先解析到根表。两种形态：入口自带 `kb_id`（列表、登记）→ 直接解析根表，**从属表一条语句都不发**；入口只有 `source_id`（同步 / 改开关 / 删）→ 先定位、再解析根，定位那条 `select` 物理上无法避免（`source_id` 只存在于从属表）但只读、不改任何状态，判定发生在紧接着的根表那一跳，跨租户在那里读作"不存在"，后续的写语句与抓取一条都不发出
- 检查放服务层不放 Controller（`WebSourceController` 的五处守卫调用全部删除，控制器只剩参数规整）：Controller 里的守卫只护得住有人记得加的那几条路径，而服务方法是所有调用方的必经之路。守卫做成独立 bean 而非 `WebSourceService` 的私有方法，是为了让这条义务可 grep、可单测、可被后续入口复用——与 `MemoryLibraryGuard` 同构
- **判定顺序是契约的一部分**：租户（404）先于数据范围（403）。反过来会让跨租户资源答 403、不存在的资源答 404，这个状态码差异本身就告诉调用方"这个 id 在别的租户里存在"。`register` 的顺序因此也变了（原先 Controller 先判数据范围），跨租户从 403 收敛为 404——**这是本次唯一的对外行为变更**，且只影响跨租户这一种本就不该成功的调用
- `syncNow` 的租户改由守卫解析出的库对象给出（`scoped.base().getTenantId()`），替代原先的二次反查：一次解析、一个租户、一次授权，避免"授权用的库"和"取凭据用的库"是两次查询的结果。定时同步链路（`scheduledSync` / `syncEnabledSources` / `sync(source, tenantId)`）**完全不走守卫，行为零变化**——那条线程没有控制台主体，需要看见全部租户的登记才能逐行反查各自的租户，这是 V22 建立的既有语义
- 单测：`WebSourceServiceTest` 新增 4 例——跨租户的 `syncNow`/`updateSettings`/`remove` 全数 404 且无写语句、无抓取、无凭据解析、无文档写入；跨租户的 `list`/`register` 全数 404 且从属表零语句；租户判定先于数据范围（同租户范围外 403、跨租户 404）；手动同步的租户取自守卫解析的库。测试用**真实守卫 + mock mapper**，跨租户表达为"根表读作 null"，与围栏在控制台线程上的真实行为一致
- **测试有效性经变异验证**：把守卫的根表解析改成不查根表后，4 个新用例全部转红且 18 个既有用例不受影响，确认它们钉住的是这条缺陷本身而非恰好为绿
- **测试边界（诚实说明）**：与 V21/V22 同样的限制——跨租户过滤由 MyBatis-Plus 拦截器完成，项目无集成测试基建（无 `@SpringBootTest`/Testcontainers），单测无法真正发出带围栏的 SQL。能钉住的是"每个入口都经根表解析"+"解析失败时后续语句一条不发"，围栏本身的行为由 `KbTenantLineHandlerTest` 覆盖
- 无 schema 变更、无新增配置键与环境变量；M12 静态抓取、M17 `render_js` 渲染、M18 登录墙检测与同 host 401 跳过四段链路行为不变

### 安全修复（M18 后修复：站点凭据多租户隔离）

- `[schema]` Flyway `V22__web_credential_tenant.sql`：`t_kb_web_credential` 增 `tenant_id`（NOT NULL DEFAULT `'tnt_default0000000'`，存量行由列 DEFAULT 划入默认租户、升级零迁移），`uk_host(host)` 收缩为 `uk_tenant_host(tenant_id, host)`。**修复的缺陷有两面**：管理面任何租户持 `system:config` 的账号能列出、改写、删除、停用其他租户为某 host 配的登录凭据（secret 不回传，但改删停用是实打实的破坏面）；抓取面凭据按 host 全局唯一、抓取也按 host 查找，B 租户只要给自己的 WebSource 登记一个同 host 的 URL，夜里的同步就会把 A 租户的密码发到那个请求上——不需要任何额外权限，也不留越权痕迹
- 不额外建 `idx_tenant`：`uk_tenant_host` 的最左前缀就是 `tenant_id`，列表页与抓取查询都走它（V17 的几张根表两个索引都建了，那是冗余，本次不照抄）
- `KbTenantLineHandler.FENCED_TABLES` 增 `t_kb_web_credential`：列表、同 host 重复校验、按 `credential_id` 改删的 SELECT、建凭据 INSERT 的 `tenant_id` 注入随行级围栏自动生效，服务层四个管理方法一个字都不用提租户
- **本次与 V21 的关键不同：入围栏只解决了一半**。抓取侧 `WebSourceService#syncEnabledSources` 跑在 `@Scheduled` 线程上，那条线程没有控制台主体，`ignoreTable` 一律返回 true、围栏整条跳过。因此 `WebCredentialService#resolveFor` 改签名为 `resolveFor(tenantId, host)`，租户做成必填入参并显式进查询条件；给不出租户时直接返回"无凭据"、**一条 SQL 都不发**，绝不退化成按 host 查。只加列不改这个签名，抓取会继续跨租户命中凭据，且从"共享一份全局凭据"这个明面上的错变成"看起来已隔离、实际仍串号"的静默错误
- 租户来源：`WebSource.kb_id` 反查 `t_kb_knowledge_base.tenant_id`（`KnowledgeBaseService` 新增不抛异常的 `find`，`require` 复用它）。控制台线程上这次反查本身也过围栏，跨租户的库读作"不存在"；定时线程上无围栏、可解析全部租户，正是同步需要的语义。知识库删除不级联删网页登记，孤儿登记解析不出租户 → 无凭据匿名抓取 → 随后 upload 失败记 FAILED，与修复前孤儿行的结局一致
- `WebSourceService#sync` 签名同步改为 `sync(source, tenantId)`，三个调用方各自给出租户：`register` 复用刚校验过的库对象（零额外查询）、`syncNow` 与批量同步经 `kb_id` 反查
- **行为变更两处**：① 同 host 凭据从全局唯一收缩为租户内唯一（两个租户各在同一 wiki 上放一个只读账号是正常业务）；② "一次认证失败就停掉该站点本轮抓取"（防 Confluence CAPTCHA 锁号）的去重键从 `host` 变为 `(租户, host)` —— 锁的是账号，两个租户在同一 host 上是两个账号，按 host 记会让一家的过期密码白白掐掉其他租户当晚的抓取
- 回归覆盖 M12 静态抓取、M17 `render_js` 渲染、M18 登录墙检测与同 host 401 跳过三段链路，行为除上述两处外不变
- 单测：`WebCredentialServiceTest` 钉住抓取查询必须带租户谓词（捕获 wrapper 断言 `tenant_id` 进 SQL 且绑定值正确）、无租户时一条语句都不发、围栏过滤后管理端改删一律 404 且不落写操作；`WebSourceServiceTest` 钉住同 host 两租户各取各的凭据、孤儿登记无凭据抓取、认证失败围栏按租户而非按 host 生效；`KbTenantLineHandlerTest` 钉住新表在有主体时入栏、无主体时跳过（后者正是抓取侧必须显式带租户的原因）
- **测试边界（诚实说明）**：管理端的跨租户拒绝由 MyBatis-Plus 拦截器完成，项目无集成测试基建（无 `@SpringBootTest`/Testcontainers），单测无法真正发出带围栏的 SQL。因此管理端能钉住的是「围栏名单包含本表」+「服务层没有绕过围栏的第二条路径」；抓取端是纯服务层代码，被直接钉死

### 修复（M14 切分策略装配缺陷）

- **separator / heading / page 三个切分策略此前保存不进去**（`docs/M14-CONTRACTS.md` §4）：`SplitStrategy` 枚举只登记了 `fixed_length`/`llm_semantic`，而 `KnowledgeBaseService#requireSplitStrategyUsable` 以该枚举为配置写入的唯一白名单，于是 M14 交付的三个策略在控制台一保存就报 `unknown split strategy: page`——实现类注册着、前端表单也齐着，整批是死代码。枚举补齐五项后策略方可选用；新增单测钉住"每个可保存的策略码都必须路由到同名实现"，避免再出现配置得上、跑的是定长。
- **按页切分绕过清洗与脱敏**（同上）：`PageSplitter` 直接消费 `parsed.json` 的 `pages[].text`，那是解析原文——页眉页脚/水印/正则替换/脱敏四步清洗与图片占位符替换全部作用在合并后的 markdown 上，从未作用在它上面，导致按页切分的知识库把未脱敏的手机号等 PII 直接写进索引，且每个分片的 `image_urls` 恒空。现改为 parser 逐页返回该页 markdown 切片、`PagedContentAssembler` 逐页清洗后拼回整篇并记录页区间（`PageRange`），`PageSplitter` 按区间下刀，与其余策略消费同一份正文；无清洗规则时逐页拼接结果与 parser 的 markdown 逐字符相等，故对非分页策略零影响。页区间随预览产物落 `page_ranges`，确认入库按存档区间切、不重算。
- **解析响应的 `pages[].markdown` 没接进来**（同上）：`HttpDocumentParserClient#toParsedDocument` 逐字段手写映射，新增字段不读就等于不存在。上一条修复因此在真实解析路径上形同虚设——每一次全新解析拿到的页 markdown 都是 null，逐页清洗回退到纯文本，占位符依旧无从谈起。两侧单测各自用手工 fixture，恰好把这条 HTTP 缝隙盖住了。补映射并新增 `HttpDocumentParserClientTest`：起本地 HTTP server 用真实响应体钉住 `pages[]` 的全部字段，含旧版 parser 不返回该字段时回退纯文本。
- **父子分片 + LLM 语义切分是假组合**（同上）：`ParentChildSplitter` 注入的是 `TextSplitter` 接口、被 `@Primary` 解析成定长实现，两级切分从来只跑定长，而分片指纹照配置记 `llm_semantic`——配置读起来是一回事、索引出来是另一回事。校验层收窄为"开启父子分片时仅允许 `fixed_length`"，同时把该依赖显式声明为 `FixedLengthTextSplitter`，让类型系统而非装配顺序来表达这个约束。
### 安全修复（M19 后修复：记忆库多租户隔离）

- `[schema]` Flyway `V21__memory_library_tenant.sql`：`t_kb_memory_library` 增 `tenant_id`（NOT NULL DEFAULT `'tnt_default0000000'`，存量行由列 DEFAULT 划入默认租户、升级零迁移）+ `idx_tenant`。**修复的缺陷**：V20 建六张记忆库表时漏了 M16 的租户层，`memory:read`/`memory:write` 只回答「这个账号能不能碰记忆库」、回答不了「能碰哪些」，于是多租户部署下任何租户持 `memory:read` 的账号能列出全部署的记忆库，持 `memory:write` 能改删其他租户的库、规则、记忆节点与 Memory Key
- 只有根聚合表加列：五张从属表（片段规则 / 画像规则 / 节点 / 画像 / Key）经 `library_id` 归属租户，与 M16 §1.1 取舍①同构——六张表全加列不叫隔离叫散弹枪，从属查询永远先过根表的租户行过滤，再多一列只是第二个可以不一致的事实源
- `KbTenantLineHandler.FENCED_TABLES` 增 `t_kb_memory_library`：库列表、详情、同名校验、建库 INSERT 的 `tenant_id` 注入随 MyBatis-Plus 行级围栏自动生效（与 `t_kb_knowledge_base` 完全同构）
- 新增 `MemoryLibraryGuard`，管理端**带 `libraryId` 的 21 个入口一律先解析库**。这一条是修复的关键：从属表不带 `tenant_id`，按 `rule_id` / `node_id` / `key_id` 直接寻址的入口（改删片段规则、改删画像规则、删记忆节点、Memory Key 的启停/轮换/删除）压根不查根表，只加列 + 进围栏对它们形同虚设。守卫做成独立 bean 而非 `MemoryAdminService` 的方法——`MemoryAppKeyService` 需要同一个检查且是前者的依赖，反向边就是循环；检查放服务层不放 Controller，Controller 里的守卫只护得住有人记得加的那几条路径
- 余下 2 个入口（库列表 `GET /`、建库 `POST /`）没有 `libraryId`，由围栏本体覆盖：列表靠 SELECT 拼租户条件，建库靠 `TenantLineInnerInterceptor` 往 INSERT 补 `tenant_id`（服务层从不 `setTenantId`，与 `KnowledgeBaseService` 同构，依赖 MyBatis-Plus 默认 `NOT_NULL` 字段策略）。**这两条是记忆库域唯一没有第二道防线的路径**，任何绕开围栏的写法（自定义 mapper SQL、`@InterceptorIgnore`）会直接抹掉它们的隔离
- **开放端行为零变化**：`MemoryKeyAuthFilter` 那条链上没有控制台主体，租户围栏整条跳过（既有语义，刻意保留——在那条线程上拼租户条件会把 Key 自己绑定的库过滤掉）；`MemoryAppKeyService.authenticate` 相应不过守卫
- **行为变更一处**：记忆库同名校验从全局唯一收缩为租户内唯一（两个租户各建一个「客服记忆库」是正常业务）
- 单测：`MemoryAdminServiceTest` / `MemoryAppKeyServiceTest` 各 2 例覆盖跨租户读写入口全数 404 且从属表一条语句都不发，`KbTenantLineHandlerTest` 钉住围栏名单与「无主体整条跳过」的开放端语义

### 新增（M20）

- MCP 协议层（`docs/M20-CONTRACTS.md`）：知识库应用与记忆库各暴露一个 MCP Streamable HTTP 端点（`POST /api/v1/knowledge/mcp`、`POST /api/v1/memory/mcp`），任何 MCP 兼容客户端配一个 URL 加一把既有 Key 即可直接调用。手写无状态 JSON-RPC 2.0 引擎（`McpServerEngine`，支持 initialize / ping / tools/list / tools/call / notifications/*，协议版本 2025-03-26 兼容 2024-11-05，不支持批量数组），**零新增依赖**。工具集：knowledge_search / knowledge_chat（仅非流式，stream=true 报 INVALID_PARAM 指路 REST SSE）与 memory_add / memory_search / memory_list / memory_update / memory_delete / memory_get_profile，参数与返回结构同 REST 孪生端点（复用 DTO，`McpArgumentBinder` 补 jakarta Validator 显式校验）。
- 鉴权复用而非新建：两个端点刻意落在 `ApiKeyAuthFilter`（kb-sk-*）与 `MemoryKeyAuthFilter`（kb-mk-*）既有 URL 前缀之下，鉴权/限流/审计与 REST 同一条链、零过滤器改动；记忆库隔离红线原样成立（库来自 Key 绑定关系，参数无法指定 library_id）。两个失败平面：协议违规回 JSON-RPC error（-32700/-32600/-32601/-32602），业务失败（BizException）回 tools/call 成功响应里 isError=true 的工具结果（文本形如「错误码: 消息」）；成功结果同时给 content 文本与 structuredContent 结构化两形态。离线单测 `McpServerEngineTest` 12 例。

### 新增（M19）

- `[schema]` Flyway `V20__memory_library.sql`：新增 6 张记忆库表——`t_kb_memory_library`（记忆库，ID 前缀 `ml`）、`t_kb_memory_fragment_rule`（记忆片段规则，`mfr`，含 `instruction_type` DEFAULT/CUSTOM、`auto_update`、`expire_days`（存天数不存枚举串，NULL 永不过期）、`extract_version` PRO/LITE、`builtin`）、`t_kb_memory_profile_rule`（画像规则，`mpr`，`fields` 整体存 JSON 数组不拆子表——字段只随规则整体编辑读取，没有按字段查询的入口）、`t_kb_memory_node`（记忆节点，`mn`，唯一高频查询路径是（库，实体）翻页，索引 `idx_library_user`）、`t_kb_memory_profile`（用户画像，`uk_rule_user` 唯一键——一实体一规则一份画像，抽取结果按此 upsert 合并）、`t_kb_memory_app_key`（Memory Key，行 ID `mak`、明文 `kb-mk-*`，与 `t_kb_api_key` 同一决策：只存 SHA-256 摘要 + 展示前缀，明文仅签发响应回传一次）；权限种子 `memory:read` / `memory:write`（module=MEMORY）授予超级管理员与知识库管理员（沿 V16 授权口径）
- 企业级记忆库（对标阿里云百炼「记忆库」）：外部智能体应用为最终用户维护**跨会话长期记忆**——对话经 LLM 抽取成记忆片段与结构化画像，后续会话按语义召回拼进提示词。开放 API 六端点（`/api/v1/memory/*`，语义与百炼 AddMemory 等一一对应）：AddMemory（`messages` 与 `custom_content` 至少传其一，custom 直写 source=CUSTOM、messages 走片段抽取，`fragment_rule_id` 缺省用库内 builtin 规则，`profile_rule_id` 需伴随 messages 同步抽画像）、SearchMemory（可选意图识别 / 查询改写 / 重排三开关，`similarity_threshold` 只在 rerank 开启时生效，`max_results` 1-100 默认 10）、ListMemory（（库，实体）分页倒序，**过期节点包含在内**——列表是管理视角，管理必须看见检索已看不见的东西）、UpdateMemory（替换 content 并重嵌入刷新 ES 副本，meta_data 传了才改）、DeleteMemory（逻辑删行 + 删 ES 副本）、GetUserProfile（未提取字段回落规则 `initial_value`）
- 两层隔离**都是查询谓词而不是约定**：一把 Memory Key 只绑定一个记忆库（应用级隔离，请求无需也无法指定 library_id），库内按 `user_id`（记忆实体）隔离；每条语句都带 `library_id` 过滤、实体级语句再加 `user_id`，交集之外的节点对调用方等于不存在，所以越权一律 **404 而不是 403**
- Memory Key 独立鉴权（`MemoryKeyAuthFilter`，第三条独立鉴权链）：`Authorization: Bearer kb-mk-*` → SHA-256 摘要查表，缺失/格式错 401 `INVALID_API_KEY`、禁用 401 `API_KEY_DISABLED`；与管理台拦截器、开放 API 的 `ApiKeyAuthFilter` 三面互不干扰，理由与 M4c 拆分开放 API 过滤器相同——凭据形态、失败面、限流口径都不同；限流复用 `ApiRateLimiter` 令牌桶（桶按 key_id 区分，两个 Key 家族的 ID 前缀不会撞），超限 429 `RATE_LIMITED`；认证通过后 `last_used_at` 异步 touch（尽力而为）；过滤器在 `@RestControllerAdvice` 之外，自写统一错误信封
- 记忆抽取与演化（`MemoryExtractionService`）：PRO 抽取 + `auto_update=1` 时加载该（库，规则，实体）最近 50 条未过期旧记忆随 prompt 下发，模型可对窗口内节点发出 UPDATE 指令（语义重复的旧记忆被覆盖而非重复追加），解析器只放行目标在窗口内的 UPDATE；LITE / `auto_update=0` 只追加。**add 刻意不是一个事务**——抽取的 LLM 调用夹在写入之间，跨 LLM 往返持连接会在中等负载下耗干连接池；每个节点写入自身原子、ES 副本紧随其后，失败最多丢一次调用的尾部（调用方可见错误、可重试）
- 检索实现：新增出站端口 `MemoryStore`（实现 `EsMemoryStore`），单物理索引 `kb_memory_nodes_v1` 所有记忆库共用——隔离靠 filter 不靠索引边界（记忆节点体量远小于文档分片，不值得按库建索引）；vector mapping **懒加载**（首个带 embedding 的写入按其维度 putMapping——嵌入维度由 Provider 声明，建索引时未必已配 Key）；有向量 kNN + BM25 并联，零 Key / 嵌入失败降级 BM25 单路——缺模型只削弱效果，绝不失败写入与检索；所有查询强制注入 `library_id` + `user_id` filter 与过期过滤；rerank 开启时候选 ×3、上限 100，未配 provider 降级召回序；命中回 MySQL 事实源 hydrate（分数序保持，行已删则静默跳过）
- 管理 API 23 端点（`/api/v1/memory-libraries`，全部 `@RequiresPermission(memory:read/write)`，写操作落审计 module=MEMORY）：库 CRUD（建库自动预置内置「默认项目」片段规则；删库级联清 Key/规则/节点/画像 + ES）、片段规则与画像规则各限每库 50 条（builtin 规则可编辑不可删除；删片段规则级联删其节点与 ES 副本；画像行物理删除——软删行会占住规则×实体唯一键）、记忆数据（实体分页 / 节点分页含过期 / 节点删除 / 画像查看）、检索调试（控制台以管理台身份复用开放 API 的 search 语义）、Memory Key 管理（创建明文仅此一次 / 启停即刻生效 / 轮换即刻失效旧密钥 / 删除）
- **无新增 Maven 依赖、无新增环境变量与配置键**：LLM 三类调用复用 `ChatProvider`/`EmbeddingProvider`/`RerankProvider` 及其零 Key 降级装置；`WebMvcConfig.PUBLIC_PATHS` 增 `/api/v1/memory/**`（该面鉴权由 MemoryKeyAuthFilter 承担，不走管理台拦截器）；存量端点与行为零变化

### 新增

- 应用重命名：控制台应用中心卡片新增「编辑」入口（仅 `app:write` 可见），复用既有 `PUT /api/v1/apps/{appId}`（`app:write`，审计 `APP/UPDATE`）更新 name 与 description，新名称与其他应用重名时拒绝（排除自身，仅改描述不改名不受此限）
- 知识库重命名：`PUT /api/v1/kb/{kbId}`（`kb:write`）更新 name 与 description，新名称与其他知识库重名时拒绝（排除自身，仅改描述不改名不受此限）；只动展示字段——索引配置与指纹不变，改名不会使任何文档 config_stale，也不触发重建；审计落 `KB/UPDATE`。控制台知识库卡片新增「编辑」入口（仅 `kb:write` 可见）
- 启动 banner 换成 KB-RAG：新增 `kb-api/src/main/resources/banner.txt` 顶掉 Spring Boot 默认图形，`KB` 两字母成一组、`RAG` 三字母各留一格、两组间双空格，靠间距读出分组因而不需要连字符（宽 41 列）；附 `${spring-boot.version}` 与构建版本 `${application.version:dev}`——jar 启动读 MANIFEST 显示实际版本，IDE 直跑没有 MANIFEST 时落到 `dev`，不留空洞。纯资源文件，不涉及任何代码与配置开关（`application.yml` 里那个 `banner: false` 是 MyBatis-Plus 的 logo 开关，与此无关）

### 新增（M18）

- `[schema]` Flyway `V19__web_credential.sql`：新增 `t_kb_web_credential`（站点级认证凭据，`host` 全局唯一）。凭据挂在 host 上而非 URL 上：同一站点登记再多 URL 只存一份，轮换只改一处。secret 与 `t_kb_ext_source.secret_key` 同一决策（D17）刻意明文存储，读接口永不回传
- 网页导入支持抓取需要登录的站点（通用能力，非 Confluence 专用）：按 host 配置凭据后，静态与 JS 渲染两条抓取路径都会对**该 host 的请求**注入认证头。两种类型覆盖所有走请求头的认证：`BASIC`（用户名+密码，预置 `Authorization: Basic`）与 `HEADER`（任意头名+值，天然覆盖 Bearer token 与 Cookie，不需要单独的 COOKIE 类型）
- 凭据注入的安全边界：host **精确匹配**、不做子域通配（通配是凭据被兄弟子域套走的经典入口）；静态抓取重定向跨 host 即剥离认证头；渲染路径在既有的 `context.route` SSRF 拦截回调里按请求逐个判 host 注入——刻意不用 `setExtraHTTPHeaders`，那会把密码广播给页面引用的每个第三方资源
- 登录墙检测（本里程碑的止血项）：需登录的页面对匿名抓取回的是**登录表单 + HTTP 200**，此前会被当正文入库（hash 落库、状态 SUCCESS、可被检索命中）。新增 `LoginWallDetector` 于 `WebPageFetcherDispatcher`（两条抓取路径的唯一出口）拦截：body 含密码输入框即判定；最终 URL 像登录端点则需标题同时像登录页才判定（保住"介绍登录页的文章"仍可入库）。命中记 FAILED + `last_error`，不入库
- 认证失败 fail-fast：Confluence 一类站点连续认证失败几次会触发 CAPTCHA 锁号。同一轮定时同步里某 host 一旦出现 401 或登录墙（`WebAuthException`），该 host 剩余登记直接记 FAILED 跳过、不再发请求——一轮最多错一次；普通抓取失败不触发该短路
- `WebPageFetcher` 端口签名收敛为 `fetch(FetchRequest)` 值对象（url + renderJs + 已解析凭据），`FetchedPage` 增 `final_url`（登录墙判定要看抓取实际落在哪个地址）。凭据由 `WebSourceService` 按 host 解析后传入，fetcher 不查库、不认识任何认证方案
- 端点：`GET/POST /api/v1/web-credentials`、`PUT/DELETE /api/v1/web-credentials/{credentialId}`，权限 `system:config`（站点级全局配置，作用于所有知识库的抓取，不归 `kb:write`）；全部变更落审计（`SYSTEM/CREATE|UPDATE|DELETE`，审计只记 host 不记 secret）。更新时 secret 缺省/空白保持原值，停启用不需要重新输入密码；host 与类型不可改，换站点即删了重建
- 控制台系统设置新增「站点凭据」Tab：列表（secret 恒显星号）、新建/编辑/删除/停启用

### 新增（M17）

- `[schema]` Flyway `V18__web_source_render_js.sql`：`t_kb_web_source` 增 `render_js TINYINT NOT NULL DEFAULT 0`（加在 `sync_enabled` 之后，语义相邻），置 1 时该源抓取走无头浏览器 JS 渲染、默认 0 静态抓取；不新增索引（不参与查询过滤，仅随行读出），存量源升级后行为零变化（继续静态抓取）
- 网页源可选 JS 渲染抓取（**按源开关、默认关**）：server 内嵌 Playwright-Java（Chromium headless），在 `WebPageFetcher` 端口后新增渲染实现，按开关路由后取渲染后 DOM 入库——解决 Oracle Javadoc 一类 frameset/SPA 页静态抓取拿不到正文的问题。渲染产物仍是一段 `text/html` 字节，**继续收敛到 M12 既有链路**（DocumentService.upload → HtmlParser → 版本机制/治理/索引），不新增任何入库旁路，`content_hash` 未变→不建新版本的判重语义天然继承
- `WebPageFetcher` 端口签名扩展 `fetch(String url, boolean renderJs)`（唯一调用点 WebSourceService，直接改签名不留重载）；新增 `WebPageFetcherDispatcher`（`@Primary`）按 `renderJs && render.enabled` 路由到静态实现（`HttpWebPageFetcher`）或渲染实现（`PlaywrightWebPageFetcher`），总闸关闭时降级静态抓取兜底
- `PlaywrightWebPageFetcher`：单个 Chromium `Browser` **懒启动**（首次真正渲染时才拉起，`@PreDestroy` 关闭），`Semaphore` 按 `max-concurrency` 限流、`timeout-ms` 内取不到令牌即 FAILED；单次渲染新建 context（禁下载、设 UA、设导航超时）→ `navigate(url, waitUntil)` → `page.content()` → UTF-8 字节，同受 `max-page-size-mb` 上限约束，finally 释放令牌与 context。浏览器启动失败抛 BizException 收成该源 FAILED，**绝不拖垮应用启动或静态抓取链路**
- 渲染路径 SSRF 防线（本期重中之重）：对渲染 context 注册 `context.route("**/*")` 路由拦截，浏览器加载的**每个子请求**（img/xhr/fetch/iframe/css 及导航重定向）逐个过既有 `UrlGuard`，命中内网/回环/链路本地/元数据地址即 `abort` 并记错误码日志（不抛，个别子资源被拦不中断整页渲染），放行则 `resume`——与静态抓取「主 URL + 逐跳过 UrlGuard」形成闭合防线
- `WebSourceService`：`register(kbId, url, syncEnabled, renderJs)` 落登记行写入 `renderJs`；`sync` 唯一改动是按 `render_js` 传参给 fetcher（其余 hash 判重/trash skip/rebind/四态/不重抛完全不变）；`updateSettings(sourceId, syncEnabled, renderJs)` 两开关均可空、只改传入项，翻开关不触发抓取；服务层用常量 `RENDER_ON=1`/`RENDER_OFF=0`，不裸用魔法值
- 端点与 DTO（既有 URL 与响应结构不变）：`POST /api/v1/kb/{kbId}/web-sources` 的 `RegisterWebSourceRequest` 增可选 `render_js`（缺省 false）；`PUT /api/v1/web-sources/{sourceId}` 的 `UpdateWebSourceRequest` 去掉 `sync_enabled` 的 `@NotNull`、增可选 `render_js`（两字段均可空）；`WebSourceResponse` 增 `render_js`（按 `RENDER_ON` 映射）；权限沿用（register/sync/update 仍 `doc:write`，list 仍 `kb:read`，渲染不新增权限）
- 指标复用既有 `kb_websource_sync_total` 四态计数（M13），不新增指标——是否渲染属抓取内部实现，不进额外维度
- 新增配置键 `kb.web-import.render.{enabled/timeout-ms/max-concurrency/wait-until}`（环境变量 `WEB_IMPORT_RENDER_ENABLED`/`WEB_IMPORT_RENDER_TIMEOUT_MS`/`WEB_IMPORT_RENDER_MAX_CONCURRENCY`/`WEB_IMPORT_RENDER_WAIT_UNTIL`），`enabled` 为总闸——关闸则忽略所有 `render_js=1` 并降级静态抓取（记 UNCHANGED/SUCCESS 同旧逻辑，不 FAILED）；复用既有 `max-page-size-mb`，不新增体积键
- 新增配置键 `kb.web-import.allow-internal-address`（环境变量 `WEB_IMPORT_ALLOW_INTERNAL_ADDRESS`，默认 false）：**危险开关**，置 true 时 `UrlGuard` 放行回环/内网地址（scheme/凭据/主机名校验照常，每次放行打 INFO 日志留痕），仅供开发联调与纯内网部署抓取内网页面使用；生产环境任何不可信用户能登记网页源时必须保持 false
- 新增依赖 `com.microsoft.playwright:playwright`（父 pom `dependencyManagement` 显式锁版本、kb-infrastructure 引入）；Chromium 二进制不走运行时自动下载，改镜像构建期安装（详见 kb-rag-deploy）

### 新增（M16）

- `[schema]` Flyway `V17__tenant_doc_acl_audit.sql`：新增 `t_kb_tenant`（内置默认租户 `tnt_default0000000` 随迁移种子化，不可停用）、`t_kb_doc_acl`（受限文档→授权角色绑定）、`t_kb_operation_audit`（操作审计，含 `username` 冗余列——账号删了记录还得可读）；6 张根聚合表（用户 / 角色 / 知识库 / API Key / 评测集 / 应用）增 `tenant_id`（存量行靠列 DEFAULT 划入默认租户，升级零迁移），从属资源经根资源归属租户，刻意不给四十张表全加列；`t_kb_role` 的 `code` 唯一范围从全局收缩为租户内（`uk_tenant_code`）；`t_kb_document` 增 `visibility`（INHERIT/RESTRICTED）、`t_kb_user_role` 增 `granted_by`（MANUAL/LDAP_SYNC）、`t_kb_retrieval_feedback` 增 `channel` / `end_user_id`；新增权限码 `tenant:manage`（仅授超级管理员）
- 完整多租户隔离：登录后主体携带 `tenant_id`，根聚合的列表 / 详情 / 写入全部按租户行过滤，跨租户的业务 id 一律 404（确认资源存在于别处本身就是泄露）；username 保持全局唯一——登录页不问租户，会话与既有审计都以 username 为键；建租户自动复制五个内置角色，但**剔除平台级权限码**——`tenant:manage` 既能建停租户又让用户表与角色表不拼租户条件，照抄给每个租户的 SUPER_ADMIN 等于每个租户管理员都能接管平台。权限码目录全租户共用，子租户在角色编辑页看得见这个码，所以授予入口 `RoleService.replacePermissions` 统一拒绝非默认租户持有它（复制时是剔除而非报错——否则建租户会整个失败）
- 租户物理索引命名隔离：默认租户之外的知识库物理索引带租户段（`kb_{租户段}_{kbId}_...`，`IndexNaming` 单点派生），默认租户沿用历史命名——存量部署的索引一个都不用动
- 租户管理端点 `/api/v1/tenants` 5 个（列表 / 详情 / 创建 / 改名 / 启停，`tenant:manage`）：code 建后不可改（索引命名的租户段由它派生），默认租户不可停用，**刻意无 DELETE**——租户名下有索引、文件与审计行，退役 = 先停用再人工清理；用户移户 `PUT /api/v1/users/{userId}/tenant`，建号可带 `tenant_id` 指定归属
- 文档级数据权限：`GET|PUT /api/v1/kb/{kbId}/documents/{docId}/visibility`（`doc:review`），`RESTRICTED` 文档仅 ACL 命中角色可读内容，检索侧（控制台 + 开放 API + 评测）统一裁剪，列表仍可见条目但内容不可读；INHERIT 时 `role_ids` 必空、RESTRICTED 时必非空，形状校验收在 Controller
- LDAP 组同步反授角色：`AUTH_LDAP_GROUP_SYNC_ENABLED` 打开后目录账号每次登录按 `AUTH_LDAP_GROUP_ROLE_MAPPINGS`（`组DN=角色CODE` 逗号分隔）全量替换其 `LDAP_SYNC` 来源的角色，**MANUAL 手工授予的角色永不触碰**——管理员手工授的角色被夜里一次登录悄悄撤掉，是排查不出来的那类事故
- 单点登录三协议（浏览器重定向流，全部免认证入口）：`GET /api/v1/auth/sso/providers`（登录页据此渲染按钮）+ OIDC（`/oidc/login`、`/oidc/callback`，授权码模式）+ SAML 2.0（`/saml/login`、`POST /saml/acs`，自实现 Response 签名验证，不引入 Spring Security SAML）+ CAS（`/cas/login`、`/cas/callback`，ticket 服务端二次校验）；回调统一 302 到 `{web-base-url}/login#sso_token=...`——fragment 不出浏览器，会话令牌不落任何一层访问日志；失败同样走 fragment 带中文原因。三协议账号来源三种新值（`source` ∈ OIDC/SAML/CAS），与 LDAP/LOCAL 同一条入口纪律：来源不符直接拒绝
- 开放 API 终端用户反馈：`POST /api/v1/knowledge/feedback`（API Key 鉴权、同链路限流），按 `request_id` 反查检索留痕补齐 kb_id 与 query（查不到 → 400，匿名反馈无法转评测用例）；落库 `channel=OPEN_API`，控制台反馈列表增 `channel` 筛选，开放渠道反馈同样可转评测用例。反馈归属由洞察行新增的 `app_id` 列校验：`request_id` 走 `X-Request-Id` 头进来、调用方可自选，只验"这个 id 存在"等于让任何合法 Key 拿到别人的 request_id 就能给无权访问的库写反馈，因此复用 API Key 已有的那道应用范围校验；跨库 `chunk_id` 同样拒绝（那次检索只可能返回本库分片），而分片**已删除**仍接受、只是不落 `doc_id`——删除不是越权信号
- 操作审计：`@AuditedOperation` 注解 + 切面异步落 `t_kb_operation_audit`，覆盖管理台全部写端点，记录谁（user_id/username/client_ip）在什么时候对什么（module/action/target）做了什么（detail 只存业务 id 与摘要，**绝不存请求体原文**——口令与文档内容都从写端点过）；查询端点 `GET /api/v1/operation-audits`（分页 + module/username/target_id/时间窗筛选）与 `/{auditId}` 详情（`audit:read`），只读设计——能编辑自己留痕的 API 就不是审计；保留期到期分批清理（默认 180 天）
- 新增环境变量 `AUTH_LDAP_GROUP_SYNC_ENABLED` / `AUTH_LDAP_GROUP_ROLE_MAPPINGS`、`AUTH_OIDC_ENABLED` / `AUTH_OIDC_ISSUER` / `AUTH_OIDC_CLIENT_ID` / `AUTH_OIDC_CLIENT_SECRET` / `AUTH_OIDC_SCOPES`、`AUTH_SAML_ENABLED` / `AUTH_SAML_IDP_ENTITY_ID` / `AUTH_SAML_IDP_SSO_URL` / `AUTH_SAML_IDP_CERTIFICATE` / `AUTH_SAML_SP_ENTITY_ID`、`AUTH_CAS_ENABLED` / `AUTH_CAS_SERVER_URL`、`AUTH_SSO_WEB_BASE_URL`、`AUDIT_OPERATION_RETENTION_DAYS` / `AUDIT_OPERATION_CLEANUP_BATCH_SIZE` / `AUDIT_OPERATION_CLEANUP_CRON`
- 文档列表批量操作：`POST /api/v1/kb/{kbId}/documents/batch-delete` 与 `POST /api/v1/kb/{kbId}/documents/batch-reindex`（均为 `doc:write`），作用域一次校验、审计一条记录。`doc_ids` **必填**——重建与确认缺省成全量是安全的，批量删除缺省成"整库"则是会造成损失的默认值；列表里混进别的知识库的文档整批拒绝（越权不该被部分执行），而已在回收站的文档、尚无版本可重建的文档被跳过而不是让整批失败（勾选与 3 秒列表轮询之间被别人删掉一篇是并发常态，不是错误），响应回真正处理掉的 id 供控制台如实提示。批量重建等同每行的「重建」按钮（完整重跑解析与索引），与按当前配置追平的 `POST /api/v1/kb/{kbId}/rebuild` 是两件事。控制台文档表的勾选相应从"仅待确认行可选"放开为任意行，并提供全选 / 反选 / 清空，批量确认改取勾选项中仍待确认的那部分，翻页即清空勾选

### 变更（M16）

- **删除知识库补齐物理索引清理（醒目提示）**：此前删库只删 MySQL 行与引擎内文档，ES/Qdrant 的物理索引壳留在引擎里（M4 遗留 TODO）。现在删库把物理索引标记后交给 CLEANUP 任务在事务提交后删除——引擎删除不可逆，绝不能发生在还可能回滚的事务里。依赖旧行为（删库后索引壳仍在）的运维脚本需调整
- SUPER_ADMIN 提权收敛提醒：M15 升级把存量账号全部提为 `SUPER_ADMIN` 并把「按最小权限重新分配」留作运维义务，现在每次启动**逐租户**检查启用状态的持有者，多于 1 人时 error 日志点名并带 `tenant_id`——只提醒、不自动降级（自动降级总会在某个部署里选错幸存者），且每次启动都提醒而非一次性标记（重启不该吞掉仍然存在的超额权限）。逐租户而非取一行：本期把角色 code 的唯一范围收缩到租户内后，`SUPER_ADMIN` 在每个租户各有一行，只看一行等于对其余租户的超权账号全程失明
- **开放 API 检索结果可能变少（醒目提示）**：文档被设为 `RESTRICTED` 后，API Key 无对应角色授权时该文档的分片从开放检索结果中裁剪。升级本身零影响（存量文档全部 INHERIT），但管理员开始使用文档级权限后对外调用方会感知结果收窄
- LDAP 登录语义微调：组同步开启后，目录账号的角色以「目录组映射 + 手工授予」的并集为准，`AUTH_LDAP_DEFAULT_ROLE_CODE` 仅在首登且组同步未命中任何映射时兜底
- **知识图谱抽取吞吐改造（醒目提示：默认并发从 2 提到 8）**：一万分片的库开启 GraphRAG 后"重新抽取"以小时计，根因是分批栅栏——每批 `allOf().join()` 等齐才提交下一批，而 LLM 延迟长尾极重，池子大半时间在批尾空转。现改为流水线：全部分片一次性排队，谁空闲谁接下一个，没有栅栏。并发默认值从 2 提到 8 的前提是把一次抽取拆成两段——**N 路并发调模型 + 单写入者线程串行落图**：图 schema 用复合索引而非唯一约束（唯一约束在社区版/企业版行为不一致），并发 MERGE 同名实体会打架，原来"并发只能是 2"正是拿正确性换速度；拆开后模型调用只受限流约束，而同一个库的 MERGE 反而比原先（两线程同时写）更严格。不做攒批写入——一批失败会连坐整批分片，违背"一个坏答案只损失一个 passage"，且图写入从来不是瓶颈。配套：进度上报按整数百分点节流并改为按列 update（`KbTask` 带乐观锁，多线程整行写会互相顶掉且失败是静默的）；抽取任务移到独立线程池 `graphTaskExecutor`（core 1 / max 2）——它是全系统唯一以小时计的任务，此前与文档解析共用 core 2 / max 4 的索引池，两个全量抽取就能让上传排在一个明天才结束的活后面。**移除 `GRAPH_EXTRACT_BATCH_SIZE`**（它描述的机制已不存在，存量 `.env` 留着不报错也不生效）；模型侧峰值并发 = 2 × `GRAPH_EXTRACT_CONCURRENCY`，限流额度紧的部署把后者调回小值即可，正确性不依赖它
- **抽取限流（429）改为退避重试，不再静默丢片（醒目提示）**：并发调高后 DashScope 开始返回 429，而抽取的单一失败处理把它和"模型答歪了"同等对待——计入 skipped、不重试。这在 429 上是错的：它的语义是"稍后再试"，且**成片到来**（额度一打穿，接下来几十个调用全是 429），于是一次抽取可能静默丢掉几百个分片，还把它们计进界面上写着「输出校验未通过」的那个数里。改为对 `QUOTA_EXCEEDED` 指数退避重试（`GRAPH_EXTRACT_RETRY_ON_THROTTLE`，默认 3，填 0 恢复旧行为）。三个设计点：①**等待发生在抽取线程内部、占着并发槽位**——限流时整次抽取自然降速到额度能承受的水平，不需要额外的信号量；②**退避带抖动**，不是装饰：所有抽取线程几乎同时被限流，固定退避会把它们一起送回去、精确复现触发 429 的那个突发；③**只重试`QUOTA_EXCEEDED`**——鉴权失败/模型不存在/输入过长重试一次也是同样结果，只会把注定失败的抽取拖长。结束日志新增 `throttleRetries=`，它是"该不该降 extract-concurrency"的唯一依据
- **图谱抽取延迟归因与降延迟改造（醒目提示：同一分片抽出的实体/关系可能比升级前少）**：流水线化 + 并发提到 12 之后，385 分片的抽取仍要 20 分钟，而**并发从 2 提到 12（6 倍）几乎没变快**——这个反常现象说明瓶颈不在并发那一段。逐段实测排除：Neo4j 写入（同形语句压测每片约 50ms，385 片共约 19 秒，占 1.6%）、热点实体度数（最大 88，MERGE 不退化）、Chunk MERGE 索引（`EXPLAIN` 确认走`NodeByLabelScan`，但 713 节点扫描是微秒级），最后落到 LLM 调用：`1200s × 12 ÷ 385 = 37.4 秒/次`。用图里的实测均值精算即可闭合——16.2 实体 + 19.9 关系序列化约 **1482 token**（其中关系占 903，因为实体名在 source/target 各重复一遍），占 `max_tokens=2048` 的 72%，qwen-plus 约 40 token/s → 37 秒，与反算吻合。结论：**抽取延迟 ≈ 输出 token 数 ÷ 生成速度，与并发无关**；且均值就占 72% 预算，长尾必然溢出——实测 35/385（9%）的分片因 JSON 被截断而整片丢失。四处改动分别打这两个因子：①抽取模型可独立配置 `GRAPH_EXTRACT_MODEL`（默认空 = 沿用 `CHAT_MODEL`）——抽取与查询改写对模型要求相反，改写是一句话要语感、抽取是照固定 JSON 填空要吞吐，turbo 档生成速度翻倍以上而"填 JSON"的质量损失远小于改写；②提示词加数量上限 `GRAPH_EXTRACT_MAX_ENTITIES`（默认 24，实体与关系共用，带下限 4 保护——0 会让提示词变成"什么都别抽"、抽取静默产出空图）并要求紧凑 JSON，常规分片碰不到上限，长尾被截在上限而不是半个JSON 上，"截断丢整片"变成"限量保主要"；③生成预算 2048 → 3072 兜长尾；④Chunk MERGE 补 `kb_id` 谓词命中 `kb_chunk_lookup` 复合索引（`EXPLAIN` 从 `NodeByLabelScan` 变 `NodeIndexSeek`）——复合索引只服务提供了前导属性的查找，原先退化为扫描全部知识库的全部 Chunk 节点、随语料增长。顺带修 `ModelProviderConfig` 派生抽取/判题 provider 时漏复制 `generateTimeoutMs`（读超时）的 bug：字段默认值恰好也是 60000 所以从未暴露，但部署方设了 `CHAT_GENERATE_TIMEOUT_MS` 时对这两条链路静默无效，而抽取答案正是全系统最长的一次生成
- **并发参数配置化（醒目提示：图谱任务池由实际恒为 1 变为 2）**：把并发提上去之后暴露出更基础的问题——这些池的大小全是硬编码常量，运维想按机器规格调只能改代码重编译；而"该调多大"本质不由代码决定，索引线程一生都在等外部服务，天花板是下游能吃下多少。新增 `INDEX_CONCURRENCY`（同时索引几个文档）、`GRAPH_TASK_CONCURRENCY`（几个知识库能同时重建图谱），取值等于原硬编码值，**不设变量的部署行为完全不变**。`GRAPH_EXECUTOR` 踩了与索引池同一个陷阱（`core=1/max=2/queue=50`，队列 50 深让 max 永不可达、实际恒为 1），配置化时一并改为 `core=max`——这是唯一的实际行为变化，模型侧峰值随之变成 `extract-concurrency × 2`。顺带修正 `extract-concurrency` 注释里"受索引池上限约束"的过时说法（抽取上一批已移到自己的池）
- **文档索引吞吐改造（醒目提示：同时索引的文档数由 2 变 4，嵌入请求速率上升）**：与图谱抽取同一诉求，落在文件解析链路上。①**嵌入批次并发化**——`ChunkEmbedder` 原按 provider 批大小切批但批次之间串行，500 个分片就是 50 次网络往返、光嵌入要半分钟到两分钟，而批次之间毫无依赖；新增 `EMBEDDING_CONCURRENCY`（默认 4）并发跑。并发上限做成共享线程池而非每次调用新建——这个值的意义是"嵌入服务同时收到几个请求"，几个文档同时索引各自开池就失控了；队列满时 `CallerRunsPolicy` 让提交者自己跑一批形成背压，不丢批次（丢一批就是丢一批向量）；单批次直接跑不进池（注解修改路径一次只嵌一个分片）。失败语义不变（一批失败即整次失败），但**解包 `CompletionException` 还原原异常**——流水线按 `BizException` 错误码区分 PARSE_FAILED 与 INDEX_FAILED，包一层既丢分支又会把 `CompletionException:` 写进运维可见的 fail_reason。②**嵌入状态按批一条语句落库**——原来每个分片一次 `updateById`，500 分片就是 500 条 UPDATE；同批状态同值，一条 `IN (...)` 即可，并从整行写改为按列写（`Chunk` 带乐观锁，并发整行写会互相顶掉且冲突无处上报）。③**索引池的 `max` 原本是死配置**——`core=2/max=4/queue=200`，而线程池只在队列满后才扩容，200 深的队列意味着稳态并发恒为 2、`max=4` 永不可达，批量上传 50 个文件是两个两个处理的；改为 `core=max=4`，让写下来的数字就是真实并发（取 4 而非机器核数：这个线程一生都在等 parser、嵌入服务与引擎，本机 CPU 上只有切分那一小段）。两级并发相乘 = 4 个文档 × 共享 4 路嵌入，嵌入池是全局上限所以不会把限流打穿
- **图片描述阶段并发化**（M3-CONTRACTS.md §7.6）：同一文档的多张图片改为按 `IMAGE_DESCRIBE_CONCURRENCY`（新增，默认 8）并发调 VLM。此前逐张串行，单次往返是 `VISION_TIMEOUT_MS`（20s）量级，图片撑满上限（100 张）的文档会独占索引管线槽位半小时以上，100 张的最坏耗时从约 33 分钟降到约 4 分钟。**资产行仍按阅读顺序串行落库**——`findByVersion` 按主键升序返回，占位符回填把它读作图片在 markdown 中的出现顺序，并发 insert 会把代理文本插到别的图片位置上；并发的只有写对象存储与 VLM 两个网络动作。单图失败不失败整篇的语义与对象存储写失败的 `fail_reason` 文本均与串行时期一致

### 新增（M15）

- `[schema]` Flyway `V16__rbac.sql`：`t_kb_admin_user` 增 `user_id` / `display_name` / `email` / `source` / `status`，`password_hash` 改为可空（目录账号没有本地口令）；新增 `t_kb_role`（角色，ID 前缀 `role`）、`t_kb_permission`（权限目录）、`t_kb_user_role`、`t_kb_role_permission`、`t_kb_role_kb`（角色→知识库数据范围）。内置 5 角色 `SUPER_ADMIN` / `KB_ADMIN` / `EDITOR` / `REVIEWER` / `VIEWER` 与 18 个权限码随迁移落库
- **存量账号一律提权为 `SUPER_ADMIN`**（含 `admin`）：否则升级后没人能进用户管理页发出第一个角色
- 功能权限：`@RequiresPermission`（`String[] value()`，any-of 语义）+ `PermissionInterceptor` 在 web 层拦一次；已铺到 24 个 Controller 共 103 处，18 个权限码全部有引用；不足时统一返回 403 `FORBIDDEN`
- 知识库级数据权限：路径带 `{kbId}` 走 `AccessGuard.requireKbAccess`，路径只带业务 id 走 `KbScopeGuard` 的 9 个 `require*Access` 反查所属库；越权同样 403，不降级为 404
- 检索与列表按可见库裁剪：`KnowledgeBaseService.list()` 对无全局范围的主体加 `kb_id IN (...)`，范围为空直接返空列表；控制台 search / chat 落到同一道裁剪
- 单点登录：新增出站端口 `DirectoryAuthenticator`（实现 `LdapDirectoryAuthenticator`，裸 JNDI simple bind，不引入 Spring LDAP），`POST /api/v1/auth/login` 增 `mode`（LOCAL/SSO，缺省读作 LOCAL），新增 `GET /api/v1/auth/sso-available`（免认证）；目录账号首登自动建号并授予配置的默认角色
- 用户与角色管理端点：`/api/v1/users` 8 个（分页列表 / 建号 / 详情 / 改资料 / 启停 / 换角色 / 重置口令 / 删除）、`/api/v1/roles` 6 个（列表 / 新建 / 权限目录 / 详情 / 修改 / 删除）
- 权限缓存 `PrincipalResolver`（进程内，按 username），改角色定义即全量失效；停用账号立即吊销其已签发会话
- 新增环境变量 `AUTH_LDAP_ENABLED` / `AUTH_LDAP_URL` / `AUTH_LDAP_DOMAIN_SUFFIX` / `AUTH_LDAP_CONNECT_TIMEOUT_MS` / `AUTH_LDAP_READ_TIMEOUT_MS` / `AUTH_LDAP_DEFAULT_ROLE_CODE`

### 变更（M15）

- 登录失败锁定口径修正：域控不可达（`DIRECTORY_UNAVAILABLE`）不计入连续失败次数——否则一次域控抖动会把所有重试过的账号一起锁 15 分钟
- `GET /api/v1/auth/me` 返回体扩展（纯新增字段）：补 `display_name` / `source` / `roles` / `permissions` / `kb_scope_all` / `kb_ids`
- 两个登录入口互不串用：本地账号走 SSO、目录账号走本地口令均直接拒绝，避免同名域账号继承本地管理员的权限

### 新增（M13）

- Prometheus 业务指标：`kb-api/pom.xml` 补齐 `micrometer-registry-prometheus` 依赖，激活既有 actuator 的 `/actuator/prometheus` 端点（JVM/HTTP 基础指标随自动配置免费提供）。业务指标经 `KbMetrics` 门面单点注册：`kb_search_seconds`（Timer，标签 source=console/open_api、zero_hit、degraded；chat-preview 管理流量不计入）、`kb_task_completed_total`（Counter，type × success/failed）、`kb_openapi_rejected_total`（Counter，按 error_code）、`kb_websource_sync_total`（Counter，按 M12 同步四态）、`kb_task_backlog`（`TaskBacklogMetrics` gauge，pending/running 两支，抓取时实查 DB，DB 异常返回 NaN 不使抓取失败）
- M13 交付时为纯新增：无表变更、无新环境变量、无既有端点行为变化；当时该端点与 health 同端口且依赖网络隔离。后续 Actuator 安全加固条目已将其迁至独立回环管理监听器

### 新增（M12）

- `[schema]` Flyway `V14__web_source.sql`：新增 `t_kb_web_source`（网页来源登记，ID 前缀 `ws`）
- URL 导入登记即抓：`POST|GET /api/v1/kb/{kbId}/web-sources`、`POST /api/v1/web-sources/{id}/sync`（手动同步）、`PUT|DELETE /api/v1/web-sources/{id}`（定时同步开关 / 移除登记）；抓取产物统一走既有文档上传管线（URL 派生稳定文件名，重抓同名建新版本、`content_hash` 去重），登记与文档为弱绑定——移除登记不删文档
- 增量同步四态（SUCCESS / UNCHANGED / SKIPPED / FAILED）落行可见：内容 hash 未变不建版本记 UNCHANGED，绑定文档在回收站则 SKIPPED；定时任务按批扫描（`WEB_IMPORT_SYNC_CRON`，默认 02:30）
- SSRF 防线收敛在 kb-domain 领域服务 `UrlGuard`：仅 http/https、拒内网 / 回环 / 链路本地地址；重定向由 `HttpWebPageFetcher` 手动跟随且逐跳复验，Content-Type 白名单、流式体积上限（`WEB_IMPORT_MAX_PAGE_SIZE_MB`）、超时控制（`WEB_IMPORT_FETCH_TIMEOUT_MS`）
- 新增出站端口 `WebPageFetcher`（实现 `HttpWebPageFetcher`）；新增环境变量 `WEB_IMPORT_FETCH_TIMEOUT_MS` / `WEB_IMPORT_MAX_PAGE_SIZE_MB` / `WEB_IMPORT_MAX_REDIRECTS` / `WEB_IMPORT_SYNC_CRON` / `WEB_IMPORT_SYNC_ENABLED` / `WEB_IMPORT_SYNC_BATCH_SIZE`

### 变更（M12）

- **上传白名单默认值变更（醒目提示）**：`UPLOAD_ALLOWED_EXTENSIONS` 默认值新增 `html`（网页抓取产物走上传管线的前提）。显式设置过该变量的部署需自行追加 `html`，否则 URL 导入首次同步即报「不支持的文件类型」；html 无魔数，与 txt/md/csv 同样仅验扩展名与大小

### 新增（M11）

- `[schema]` Flyway `V13__document_governance.sql`：`t_kb_document` 增 `publish_status` / `review_note` / `effective_at` / `expires_at` / `trashed` / `trashed_at`，`t_kb_knowledge_base` 增 `review_required`；存量文档升级后默认 PUBLISHED / 无有效期 / 不在回收站，检索结果与升级前一致
- 审核发布：知识库级 `review_required` 开关（`PUT /api/v1/kb/{kbId}/governance`），开启后新上传文档初始为 DRAFT，经 submit-review / approve / reject 状态机（DRAFT|REJECTED → PENDING_REVIEW → PUBLISHED|REJECTED）发布后才参与检索
- 文档有效期：`PUT /api/v1/documents/{docId}/validity` 设置 / 清除 `effective_at` / `expires_at` 窗口，仅窗口内参与检索，`expires_at` 设为过去即立即下架
- 回收站：trash 列表 / restore / purge 端点，超过保留期（`TRASH_RETENTION_DAYS`，默认 30）由定时任务分批物理清除
- 治理三态均收敛为检索时的活跃集 DB 谓词过滤，不写引擎，状态变更即时生效（时间窗穿越靠缓存 TTL 5 分钟内收敛）；已发布应用快照固化可见集，不受治理影响
- 新增环境变量 `TRASH_RETENTION_DAYS` / `TRASH_PURGE_BATCH_SIZE` / `TRASH_PURGE_CRON` / `TRASH_PURGE_ENABLED`

### 变更（M11）

- **`DELETE /api/v1/documents/{docId}` 语义变更（醒目提示）**：URL 不变，但删除由不可逆改为移入回收站（检索立即下线、数据保留、保留期内可 `POST /api/v1/documents/{docId}/restore` 还原）；原来的不可逆删除（含两个检索引擎副本）迁移至 `DELETE /api/v1/documents/{docId}/purge`，且仅对回收站内文档有效（两段式防误删）。依赖旧语义的调用方需改为先 DELETE 再 purge

### 新增（M10）

- `[schema]` Flyway `V12__retrieval_feedback_and_search_insight.sql`：新增 `t_kb_retrieval_feedback`（ID 前缀 `rfb`）与 `t_kb_search_insight`（ID 前缀 `si`）
- 检索反馈从 log-only 升级为持久化闭环：`POST /api/v1/retrieval-feedback` payload 不变（兼容红线）但落库为可管理行（有用 / 无用 + 原因，幂等键防重复提交）；新增知识库维度的反馈列表、转评测集（case source=FEEDBACK）与忽略端点
- 检索洞察：控制台调试与 OpenAPI 检索自动记录脱敏摘要 / 命中数 / 降级标记（评测运行不记录，不存原文）；新增明细分页与内容缺口报表端点（零命中率 / Top 未命中 query 归一化分组）
- 洞察行保留期清理（`INSIGHT_RETENTION_DAYS`，默认 90）：统计而非证据，到期分批直删、不做对象存储归档
- 新增环境变量 `INSIGHT_ENABLED` / `INSIGHT_RETENTION_DAYS` / `INSIGHT_CLEANUP_BATCH_SIZE` / `INSIGHT_CLEANUP_CRON`

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
