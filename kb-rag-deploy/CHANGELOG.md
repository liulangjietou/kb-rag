# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 新增 `scripts/validate_config.py` 与单元测试，统一拦截 `.env.example` 重复键、开发机绝对路径和
  两份需求文档漂移；校验已接入 monorepo 根级 CI。

### Changed

- 四个历史子仓库工作流收敛为根目录单一 CI 入口，Java、Python、Web 与部署契约并行验证。

### Fixed

- 修正 `GRAPH_EXTRACT_MAX_TOKENS` 在 `.env.example` 中重复且默认值冲突的问题，统一为服务端实际
  默认值 3072；Demo 目录改为仓库相对路径；full 模式内存说明统一为当前预检执行的 16GB。

- **角色编辑抽屉不回显已有配置，角色列表跨租户不可辨识（M15 后修复，`docs/M15-CONTRACTS.md` §3.3）**：控制台角色管理页点"编辑"，抽屉里名称、说明、数据范围与**已授权限的勾选**全是空的。不是权限没存住，是压根没读出来——回填代码在抽屉打开**之前**执行，那一刻表单还没渲染，赋值被前端框架丢弃。**后果不止是看不见**：运维要么反复重勾，要么在一张空表单上直接点保存，把该角色已有的授权覆盖成空集，持该角色的账号下一次请求就少掉一批菜单和接口。改为随抽屉挂载一次性带入初值，并让表单每次打开都重建，避免快速关掉再打开时残留上一个角色的值。**同页第二处**：平台运维（持租户管理权限的默认租户超管）读角色列表本就不受租户行过滤限制——这是必需的放行，否则新建的租户没人能给它授第一个角色——而每个租户都有一份照抄的五个内置角色，编码与名称完全相同，返回体里没有归属字段，页面于是呈现为"每个内置角色重复 N 遍"的表，**运维在猜自己编辑的是哪一家的角色**。角色行补"所属租户"字段，表格在持租户管理权限时多渲染一列（租户名复用租户列表接口，与用户管理页同一手法）；其余账号读到的本就只有自己租户那一份，页面不变。**无 Flyway 脚本**（`tenant_id` 列 V17 就已存在，本次只是把它带进返回体）、**无新增环境变量与配置键，升级零操作**；开放 API 与后台线程不读这两个接口，行为零变化。**唯一对外行为变更**：`GET /api/v1/roles` 与 `/roles/{roleId}` 返回体多一个 `tenant_id` 字段，纯新增、存量调用方不受影响。同步修订：`ARCHITECTURE.md` 升 v2.2（§7.2 补"围栏放行分支的代价不是隔离、是可辨识性"一条）、`M15-CONTRACTS.md` §3.3 补角色行租户归属说明、OpenAPI `kb-server.yaml` 升 `0.22.0-m15-fix`（`Role` schema 增 `tenant_id`、角色列表端点描述注明跨租户返回口径——**本条是真的 schema 新增字段，与此前几条"纯描述订正"不同**）。

- **按 `kbId` 寻址的列表与批量入口不解析根表（M16 后修复，`docs/M16-CONTRACTS.md` §1.3.2）**：上一条修的是"路径只带从属资源 id"那一类入口，这一条是另一类——**路径自带 `kbId`、但链路上从头到尾没有一次对知识库表的查询**。这类入口最容易被误判为安全，因为路径里那个 `kbId` 看着就是作用域本身；可它是**调用方声明的**作用域，不是被证实的。守卫只有 Controller 里那行"库在不在你角色配的数据范围里"，而 `kb_scope_all` 对五个内置角色恒为真，于是报一个别家的 `kbId`，后续按 `kb_id` 过滤的语句照常执行：列出别家知识库的全部文档与解析状态、读它的回收站（删除历史）、读它的检索洞察（**含用户搜过的原始 query 文本**）、批量把它的文档丢进回收站或重跑索引、替它跑一遍全库重建、读改它文档的密级与授权角色。文档密级那两条另有一层：原先只校验"文档挂在这个 `kbId` 下"，跨租户调用方把别家的 `kbId` 与该库下的 `docId` 一起传进来**完全对得上**——"从属行属于这个父"和"这个父属于你"是两个问题，只问了前一个。修复落在服务层而非 Controller（服务方法是所有调用方的必经之路）：文档列表与批量作用域校验、批量确认、回收站列表、全库重建与状态、洞察列表与统计、文档密级读写，首行一律先经行级围栏读回该库，跨租户读作"不存在" → **404**；28 处 Controller 的数据范围调用统一换成 `KbResourceGuard#requireKb`，**判定顺序由此在全域一致**（租户 404 先于数据范围 403，原先 Graph、chat 导入、检索、知识库增改删这些服务层已有解析的入口顺序是反的，403 与 404 的差异会泄露"这个 id 在别的租户里存在"）。**无 Flyway 脚本、无新增环境变量与配置键，升级零操作**；开放 API 与定时任务链路不经这些方法，行为零变化。**唯一对外行为变更**：跨租户由 403（或成功）收敛为 404，同租户数据范围外仍是 403。同步修订：`M1-CONTRACTS.md` §5、`M10-CONTRACTS.md` §2.2、`M11-CONTRACTS.md` §2.2 各补本域解析义务说明，OpenAPI `kb-server.yaml` 的 knowledge-base / insight 两个 tag 补总述、批量删除与全库重建与文档密级三个端点逐条注明（**纯描述订正，schema 与版本号不变**）。

- **按资源自身 id 寻址的入口整族缺少租户解析（M16 后修复，`docs/M16-CONTRACTS.md` §1.3.2）**：上一条网页源修复删掉的 `KbScopeGuard#requireWebSourceAccess` 不是孤例，是一族里的一个——同类的另外 8 个方法（document / chunk / annotation / dataset / case / run / ext-source / feedback）逐字同构：第一行 `kb_scope_all` 短路、随后只判"库在不在调用者角色配的数据范围里"，**一行租户判断都没有**。而五个内置角色一律带 `kb_scope_all`、建租户时照抄，所以那个短路在真实部署里对租户超管与未配数据范围的知识库管理员恒成立：**这些守卫的开销为零、判定恒为放行**，站在它们后面的控制台入口全部跨租户可达。破坏面最大的几条：覆写别家外部数据源的 endpoint 与 AK/SK、拿别家凭据向别家对象存储发外网探测请求、**硬删**别家数据源登记（不可恢复）、彻底清除别家文档、把别家文档回滚到旧版本并重跑索引、读别家评测明细。修法与 `WebSourceGuard` / `MemoryLibraryGuard` 同构：类重命名为 `KbResourceGuard`，9 个方法一律"先解析围栏根表（跨租户读作不存在 → **404**）、再判数据范围（403）"，短路整体删除；服务层同步补齐不经 Controller 守卫的调用方。**顺带钉住一条更普适的教训**：`requireDatasetAccess` 查的评测集表本来就在行级围栏名单里、围栏会自动拼租户条件，但方法第一行的 `return` 让那条语句压根不执行——**围栏只保护它实际发出的语句**，任何提前返回都会连同已写好的围栏一起跳过，而这种失败在方法体里看不出来，因为围栏是拦截器。**无 Flyway 脚本、无新增环境变量与配置键，升级零操作**；开放 API（`Bearer kb-sk-*`）与后台线程行为零变化（那些线程无控制台主体，围栏与数据范围整条跳过，是 M16 建立的既有语义）。**唯一对外行为变更**：跨租户访问这批端点由 403（或成功）收敛为 404，只影响本就不该成功的调用；同租户、数据范围外仍是 403。同步修订：`ARCHITECTURE.md` 升 v2.0（§7.2 补全族说明）、`M16-CONTRACTS.md` §1.3.2 表增两行与两条说明、`M15-CONTRACTS.md` §4.2 对"全范围调用者连反查那一次查询都不付"加删除线与推翻说明、OpenAPI `kb-server.yaml` 相关端点描述注明跨租户 404（**纯描述订正，schema 与版本号不变**）。

- **线程池形状、拒绝后的状态自锁与 requestId 断链（M4b/M4c 异步化后修复，`docs/ARCHITECTURE.md` §3.7）**：上一次修复把评测与门禁的 `@Async` 自调用换成显式 Executor 手工 `execute`（修复本身是对的），但那让四条"看起来异步、实际同步"的路径第一次真的进队列——**永不排队就永不拒绝**，四个问题同时从理论变成现实。①**池的 max 是个到不了的数字**：`ThreadPoolTaskExecutor` 只在队列满后才扩容，`evalTaskExecutor` `core=2/max=6/queue=50` 稳态并发恒为 2（而它的 javadoc 写着"6 个 run 并行"），`gateTaskExecutor` `core=1/max=4/queue=20` 同病。改为 6/6/50 与 4/4/20，`auditTaskExecutor`、`extSourceTaskExecutor` 按**当前真实并发**收敛为 1/1（行为零变化，只是不再骗人）；规则本身做成 `AsyncConfigTest`，反射遍历全部池断言"要么 queue==0、要么 core==max"，这条已踩过两次。②**被拒后的状态自锁**：两处提交的 try/catch 都写在 lambda 内部，`execute()` 本身没保护，而两处都发生在状态已落库之后——评测留下永不执行的 `PENDING` 孤儿行，门禁更严重，版本永久停在 `GATING` 而 `release` 入口恰好拒绝从 `GATING` 再发布，**自锁只能改库**。两池保留 `AbortPolicy`（换 CallerRuns 会把整条 run 拽回 HTTP 请求线程，正是上次修掉的形态），兜底改写在提交处：被拒的 run 就地改判 `FAILED`、被拒的门禁记 `LOG_ONLY/RUN_FAILED`。③**门禁 30 分钟预算第一次可触达**：超时落非通过但可重试的裁决是正确答案，但这是修复前不存在且零覆盖的路径，补测并额外断言不读未完成 run 的 case 行。④**requestId 在 CallerRuns 上断链**：装饰器 finally 无条件 `MDC.remove`，队列满时任务回跑在提交者线程上、跑完清掉提交者自己的 id，那条 run / 那次索引后半段日志全部失关联；改为保存并恢复原值。**另新增 `EvalRunCompensationService`**（fixedDelay 5min）扫进程崩溃留下的 `PENDING`/`RUNNING` 评测 run 改判 FAILED——只改判不重跑（重跑会让 per case 行翻倍污染指标）、走 wrapper update 不走 `updateById`（不碰乐观锁，被早收的慢 run 自己那次写入仍能落地）。**无 Flyway 脚本；新增 4 个配置键**（`kb.eval.stuck-scan-enabled` / `stuck-scan-interval-ms` / `stuck-timeout-minutes` / `stuck-scan-batch-size`，均带默认值，升级零操作）。**同时补上上一次修复的静默行为变更**：`kb.eval.concurrency` 语义已从"每 run 的 case 并发"变为"全部在跑评测的**全局** case 并发"，默认吞吐较之前净降约 6 倍，此前 yml 无注释、CHANGELOG 无条目、架构文档未提，本次在三处补齐。
- **应用版本按 id 寻址的入口缺少租户解析（M4c 后修复，`docs/M16-CONTRACTS.md` §1.3.2）**：`t_kb_app_version` 是经 `app_id` 归属租户的从属表，不带 `tenant_id` 也不进行级围栏——这个设计是对的，漏的是解析：`AppVersionService#require` 是从属表上的裸 `selectOne`，从不查根表 `t_kb_app`，而 `/api/v1/app-versions/{appVersionId}` 的五个端点（详情、绑定门禁评测集、提交测试、发布、回滚）只有功能权限码、没有任何租户守卫。任何租户持 `app:release` 的账号凭一个 `appVersionId` 就能**发布或回滚别家租户的应用版本**（直接改变别人对外 API 被服务的内容），持 `app:read` 就能读它的配置快照（含关联知识库与模型配置）；发布还会在门禁执行器上对别家知识库启动同语料双跑，花掉他们的检索与模型调用。新增 `AppVersionGuard` 让入口一律先解析到根表，跨租户读作"不存在"→ **404**（与 V21/V22 及网页源同口径）。守卫落在 `AppVersionService#require` 背后而非各入口前面：该方法有 11 处调用方（本服务 5、`ReleaseGateService` 5、控制台预览 1），放入口必漏。**跨租户与不存在返回同一错误码同一文案**，报成 `APP_NOT_FOUND` 会用差异泄露"这个 id 在别的租户里存在"。**无 Flyway 脚本、无新增环境变量与配置键，升级零操作**；对外 `search`/`chat` 走 `resolveForCall` 不经该方法、由 API Key 的授权范围把关，**行为零变化**，门禁执行器与预览流线程同样不受影响（无控制台主体，围栏本就整条跳过）。**唯一对外行为变更**：跨租户访问这五个端点由"成功"收敛为 404，只影响本就不该成功的调用；同一 PR 内 `gate-dataset` 的判定顺序改为租户先于数据范围（数据范围检查从 Controller 移入服务层）。同步修订：`ARCHITECTURE.md` 升 v1.9（§7.2 + Flyway 一览 V6 行注明该表刻意不带 `tenant_id`）、`M16-CONTRACTS.md` §1.3.2 表增行、`M4c-CONTRACTS.md` §2 补租户解析条、OpenAPI `kb-server.yaml` 五个端点描述注明跨租户 404（**纯描述订正，schema 与版本号不变**——这些端点本就只声明 404）。

- **网页源按 id 寻址的入口缺少租户解析（M12/M17/M18 后修复，`docs/M16-CONTRACTS.md` §1.3.2）**：`t_kb_web_source` 是经 `kb_id` 归属租户的从属表，不带 `tenant_id` 也不进行级围栏——这个设计是对的，漏的是解析：按 `sourceId` / `kbId` 直接寻址的四个入口压根不查根表 `t_kb_knowledge_base`，围栏在那几条语句上什么都没做。任何租户凭一个 `sourceId` 就能触发别家网页源的抓取、改它的同步与 JS 渲染开关、**硬删**它的登记（不可恢复），凭一个 `kbId` 就能列出别家知识库登记的全部 URL 与同步状态。新增 `WebSourceGuard` 让四个入口一律先解析到根表，跨租户读作"不存在"→ **404**（与 V21 记忆库同口径），与 `MemoryLibraryGuard` 同构。**根因**：原先站在这些入口前面的 `KbScopeGuard#requireWebSourceAccess` 回答的是"库在不在调用者的数据范围里"、一行租户判断都没有，且第一行的 `kb_scope_all` 短路对租户超管与未配数据范围的知识库管理员直接放行——只覆盖数据范围的守卫比没有守卫更危险，它让 review 以为路径已守住，该方法已删除。**无 Flyway 脚本、无新增环境变量与配置键，升级零操作**；定时同步链路行为零变化（那条线程无控制台主体，需要看见全部租户的登记才能逐行反查租户，是 V22 建立的既有语义）。**唯一对外行为变更**：跨租户访问这五个网页导入端点（含登记）由 403 收敛为 404，只影响本就不该成功的调用。同步修订：`ARCHITECTURE.md` 升 v1.8（§7.2 + Flyway 一览 V14 行注明该表刻意不带 `tenant_id`）、`M12-CONTRACTS.md` §3.4 补租户解析条、OpenAPI `kb-server.yaml` 五个网页源端点描述注明跨租户 404（**纯描述订正，schema 与版本号不变**——这些端点本就只声明 404、未声明 403）。

- **网页导入站点凭据的多租户隔离（M18 后修复，`docs/M16-CONTRACTS.md` §1.3/§1.3.1）**：M18 的 `t_kb_web_credential` 漏了 M16 的租户层，缺陷有两面——管理面任何租户持 `system:config` 的账号能改删停用其他租户为某 host 配的登录凭据（secret 不回传，改删停用仍是实打实的破坏面）；抓取面凭据按 host 全局唯一、抓取也按 host 查找，另一租户只要给自己的网页源登记一个同 host 的 URL，夜里的同步就会把别家的密码发到那个请求上。Flyway `V22__web_credential_tenant.sql` 补 `tenant_id`（存量行由列 DEFAULT 划入内置默认租户，**升级零迁移、单租户部署行为不变**）并把 `uk_host` 收缩为 `uk_tenant_host`。**与 V21 的关键不同：入围栏只解决管理面**——抓取跑在 `@Scheduled` 线程上，那条链没有控制台主体、围栏整条跳过，因此 `WebCredentialService#resolveFor(tenantId, host)` 把租户做成必填入参并显式进查询，给不出租户就返回"无凭据"、一条 SQL 都不发；租户由网页源的 `kb_id` 反查知识库得到。**行为变更两处**：同 host 凭据由全局唯一收缩为租户内唯一；"一次认证失败停掉该站点本轮抓取"（防 Confluence 锁号）的去重键由 `host` 变为 `(租户, host)`。**无新增环境变量与配置键**，M12 静态抓取 / M17 JS 渲染 / M18 登录墙检测三段链路行为不变。`ARCHITECTURE.md` 升 v1.7、OpenAPI 凭据端点描述同步订正。


- **M14 切分策略装配缺陷的文档回补**（`docs/M14-CONTRACTS.md` §4 v1.1 修订条）：三处"文档写了、代码没那么跑"的偏差按铁律在同 PR 内改文档。①M14 交付的 separator/heading/page 三策略因漏登记进 `SplitStrategy` 枚举，在配置写入路径被拒、整批是死代码；②`page` 策略契约初版写的"从 parsed.json 取分页文本"会绕过清洗四步与图片占位符替换（PII 未脱敏入索引、分片 `image_urls` 恒空），改为消费逐页清洗后的正文 + 页区间；③父子分片可组合策略由 fixed_length/llm_semantic 收窄为仅 fixed_length，与 `M4b-CONTRACTS.md` §4 统一。同步修订：需求文档升 v1.19（§4.3 父子分片"正交"结论按实现纠正）、`M3-CONTRACTS.md`（parse 响应与预览产物形状、§7.2 管线次序补逐页清洗）、`M1-CONTRACTS.md`、`ARCHITECTURE.md` 升 v1.6、`FLOWS.md` 升 v1.5、`CONTRACT-ALIGNMENT-2026-07-27.md` 加现状后记。
- OpenAPI 升版：`kb-server.yaml` → `0.21.0-m14-fix`（`DocumentPreview` 增 `page_ranges`、`ParsePreviewPage` 增 `markdown`、新增 `ParsePreviewPageRange` schema、`SplitStrategy` 描述改父子组合口径）；`kb-parser.yaml` → `0.14.0-m14`（`ParsePage` 增 `markdown` 切片字段）。**均为纯新增字段**，存量调用方与既有产物不受影响（缺字段时消费方回退旧行为）。
- **记忆库多租户隔离（M19 后修复，`docs/M19-CONTRACTS.md` §1.4）**：M19 的六张记忆库表漏了 M16 的租户层——`memory:read`/`memory:write` 只回答「这个账号能不能碰记忆库」、回答不了「能碰哪些」，多租户部署下任何租户持 `memory:read` 的账号能列出全部署的记忆库，持 `memory:write` 能改删其他租户的库、规则、记忆与 Memory Key。Flyway `V21__memory_library_tenant.sql` 给 `t_kb_memory_library` 补 `tenant_id`（存量行由列 DEFAULT 划入内置默认租户，**升级零迁移、单租户部署行为不变**）并入行级围栏；五张从属表不加列、经 `library_id` 归属租户；管理端带 `libraryId` 的 21 个入口一律先解析库（`MemoryLibraryGuard`），否则按 `rule_id`/`node_id`/`key_id` 直接寻址的入口不经过带租户列的那张表、围栏形同虚设；余下 2 个（库列表、建库）无 `libraryId`，由围栏本体直接覆盖。**开放端（`Bearer kb-mk-*`）行为零变化**：那条过滤器链上没有控制台主体，围栏整条跳过，隔离仍由 Key 绑库 + `user_id` 两层查询谓词完成。**唯一行为变更**：记忆库同名校验从全局唯一收缩为租户内唯一。`ARCHITECTURE.md` 升 v1.6、`FLOWS.md` 升 v1.5。

### Added

- **MCP 协议层（M20，`docs/M20-CONTRACTS.md`）**：知识库应用与记忆库各暴露一个 MCP Streamable HTTP 端点（`POST /api/v1/knowledge/mcp`、`POST /api/v1/memory/mcp`，JSON-RPC 2.0 单请求单 JSON 响应，协议版本 2025-03-26 兼容 2024-11-05），任何 MCP 兼容客户端（Claude Desktop / Cursor / Cline 等）配一个 URL 加一把既有 Key（kb-sk-* / kb-mk-*）即可直接调用。鉴权/限流/审计与 REST 开放端点同一条过滤器链；工具集 knowledge_search / knowledge_chat（仅非流式）与 memory 六工具，参数与返回结构同 REST 孪生端点。**无新增容器、依赖、环境变量与配置键**，纯新增、存量端点与行为零变化。控制台新增「MCP 调试」一级菜单；调用方文档见主仓 `docs/MCP接入指南.md`。
- OpenAPI 升至 `0.20.0-m20`：新增 `mcp` tag、两个 MCP path 与 `McpJsonRpcRequest` / `McpJsonRpcResponse` schema。
- **企业级记忆库（M19，`docs/M19-CONTRACTS.md`）**：对标阿里云百炼「记忆库」——外部智能体应用通过 **Memory Key（`kb-mk-*`）** 调用开放 API（`/api/v1/memory/*` 六端点：Add/Search/List/Update/Delete/GetUserProfile），为最终用户维护跨会话长期记忆：对话经 LLM 抽取成记忆片段与结构化画像，后续会话按语义召回（向量 + BM25 混合，可选意图识别/改写/重排）。控制台新增「记忆库」一级菜单（库/片段规则/画像规则/记忆数据/检索调试/Memory Key 管理）。Flyway `V20__memory_library.sql` 新增 6 张表与 `memory:read`/`memory:write` 权限种子。记忆节点写入 ES 单物理索引 `kb_memory_nodes_v1`（隔离靠 `library_id` + `user_id` 查询谓词，一把 Key 只绑定一个库）。**无新增容器、环境变量与配置键**——LLM 三类调用复用既有 `DASHSCOPE_API_KEY` 等模型配置，零 Key 部署降级 BM25 单路检索、抽取报错可见；纯新增，存量端点与行为零变化。调用方文档见主仓 `docs/记忆库接入指南.md`。
- OpenAPI 升至 `0.19.0-m19`（M18 未递增版本号，本次一并补账）：新增 `memory-library`（管理端 23 路径）与 `memory-open-api`（开放端 6 路径，`Authorization: Bearer kb-mk-*` 鉴权）两组端点及记忆库全套 schema。
- **网页导入站点凭据与登录墙检测（M18）**：抓取需要登录的站点（内网 wiki、私有文档站）的通用能力。新增 `t_kb_web_credential`（Flyway `V19`，host 全局唯一），BASIC / HEADER 两种类型覆盖所有走请求头的认证；凭据只对精确匹配 host 的请求注入，静态路径重定向跨 host 剥离、渲染路径在 SSRF 拦截回调里逐请求判定。新增登录墙检测：登录表单 + 200 此前会被当正文入库（真实事故），现于两条抓取路径的唯一出口拦截并记 FAILED；同一轮同步同 host 首个 401/登录墙后剩余登记直接跳过（防 Confluence CAPTCHA 锁号）。OpenAPI 新增 `/api/v1/web-credentials` 系列端点与 `WebCredential` schema（响应结构上就没有 secret 字段）。**无新增环境变量**；secret 与 S3 凭据同一决策（D17）明文存库，建议站点侧使用专用只读账号并配合网络隔离部署。
- **网页导入 JS 渲染抓取（M17，`docs/M17-CONTRACTS.md`）**：网页源新增可选 `render_js` 开关（**按源、默认关**），开启后该源抓取走 server 内嵌 Playwright-Java（Chromium headless）渲染后取 DOM 入库，解决 Oracle Javadoc 一类 frameset/SPA 页静态抓取拿不到正文的问题；渲染产物仍走 M12 文档上传管线，不新增入库旁路。SSRF 防线覆盖浏览器加载的**每个子请求**（逐个过 `UrlGuard`，拦内网/回环/元数据地址）。`.env.example` 新增「网页导入 JS 渲染（M17）」分节：`WEB_IMPORT_RENDER_ENABLED`（默认 true，总闸；关闸则忽略所有 `render_js=1` 并降级静态抓取，行为等同 M12）/ `WEB_IMPORT_RENDER_TIMEOUT_MS`（默认 20000）/ `WEB_IMPORT_RENDER_MAX_CONCURRENCY`（默认 2，与容器内存联动）/ `WEB_IMPORT_RENDER_WAIT_UNTIL`（默认 networkidle），全部带默认值——**不设变量与未内置 Chromium 的部署行为不变**。**部署红线**：kb-rag-server 镜像需构建期内置 Chromium 及其运行库与常用字体（`playwright install --with-deps chromium` 或等价系统包）并设 `PLAYWRIGHT_BROWSERS_PATH` 指向镜像内固定路径；**镜像体积与内存显著上升**（Chromium 单 context 数百 MB 级，渲染实例内存建议在原基础上上调，与 `WEB_IMPORT_RENDER_MAX_CONCURRENCY` 联动）；**未内置 Chromium 的镜像误开 `render_js` 只会让该源同步 FAILED 且原因可见，不拖垮应用启动与静态抓取链路**。纵深防御建议：渲染实例网络出站仅放行公网、显式屏蔽元数据网段，作为代码防线之外的兜底。
- OpenAPI 升至 `0.17.0-m17`：`RegisterWebSourceRequest` / `UpdateWebSourceRequest` / `WebSource` schema 三处增 `render_js`（默认 false）；PUT 请求体去 `required: [sync_enabled]`（两开关均可选，只改传入的那个）。
- **企业化：多租户 / 文档级数据权限 / LDAP 组同步 / SSO 三协议 / 操作审计（M16）**：新增 `docs/M16-CONTRACTS.md`
  （开发契约，含租户模型与索引命名租户段、文档 ACL 判定点、三协议 SSO 行为与升级说明）；
  `.env.example` 新增「企业化（M16）」分节：`AUTH_LDAP_GROUP_SYNC_ENABLED`（默认 false）/
  `AUTH_LDAP_GROUP_ROLE_MAPPINGS`（`组DN=角色CODE` 逗号分隔）、`AUTH_OIDC_ENABLED` /
  `AUTH_OIDC_ISSUER` / `AUTH_OIDC_CLIENT_ID` / `AUTH_OIDC_CLIENT_SECRET` / `AUTH_OIDC_SCOPES`、
  `AUTH_SAML_ENABLED` / `AUTH_SAML_IDP_ENTITY_ID` / `AUTH_SAML_IDP_SSO_URL` /
  `AUTH_SAML_IDP_CERTIFICATE` / `AUTH_SAML_SP_ENTITY_ID`（默认 `kb-rag`）、`AUTH_CAS_ENABLED` /
  `AUTH_CAS_SERVER_URL`、`AUTH_SSO_WEB_BASE_URL`（SSO 回调落地的控制台地址，开任一协议必填）、
  `AUDIT_OPERATION_RETENTION_DAYS`（默认 180）——全部缺省即升级前行为。**升级提示**：Flyway
  `V17__tenant_doc_acl_audit.sql` 把存量用户 / 角色 / 知识库 / API Key / 评测集 / 应用全部划入
  内置默认租户（存量索引命名不变，零迁移）；删除知识库从本版起会一并删掉 ES/Qdrant 物理索引
  （此前只删数据行留索引壳）；文档设为 RESTRICTED 后无授权的 API Key 检索结果会相应变少；
  开放反馈端点开始校验 request_id 的应用归属，只用自己检索返回的 request_id 提交反馈不受影响。
- OpenAPI 升至 `0.16.0-m16`：新增 `/api/v1/auth/sso/providers` 与 OIDC/SAML/CAS 六个登录回调端点
  （全部免认证，302 + URL fragment 交付）、`/api/v1/tenants` 租户管理五端点、`/api/v1/users/{userId}/tenant`
  移户、`/api/v1/kb/{kbId}/documents/{docId}/visibility` 文档可见性读写、`/api/v1/operation-audits`
  操作审计列表与详情、`/api/v1/knowledge/feedback` 开放反馈（API Key 鉴权）；`ConsoleUser` 补
  `tenant_id`、`source` 枚举扩 OIDC/SAML/CAS、`RetrievalFeedback` 补 `channel` / `end_user_id`、
  `Document` 补 `restricted`，新增租户 / 可见性 / 审计 / 反馈相关 schema 18 个。
- 需求文档升至 v1.18：收口 M14/M15/M16 交付记载，权限延后清单销账（多租户 / 文档级权限 /
  组同步 / 操作审计均已交付），与主仓 `docs/` 副本逐字一致。
- **图片阶段吞吐优化（回补进 `docs/M3-CONTRACTS.md` §2.1/§7.6）**：①parser 对 pdf 内嵌图片按图片
  对象（xref）去重——页眉页脚 logo 这类「一个对象画在每一页」的图按出现位置计数，会让
  247 页文档报出 493 张图、刷出几百条 warning，实测一份 2.7MB 国标省市区 pdf 的 `images[]`
  从 100 降到 3、响应体 base64 从 1.7MB 降到 0.03MB、解析耗时 1.59s → 0.77s；图片数已达上限
  且 `OCR_ENGINE=none` 时扫描页不再白渲染（渲出的 PNG 无人可读），220 页扫描件 29.34s → 13.54s；
  ②server 侧图片描述改为并发，`.env.example` 新增 `IMAGE_DESCRIBE_CONCURRENCY`（默认 8）——
  一张图一次 VLM 往返（`VISION_TIMEOUT_MS` 量级），串行时撑满上限的文档占着索引槽位半小时，
  100 张的最坏耗时从约 33 分钟降到约 4 分钟；并发度上界看模型服务方限流而不是本机 CPU（被限
  流的调用记 FAILED 且不重试）。两项均不改变对外行为：占位符回填仍按阅读顺序，单图失败仍不
  失败整篇。`MAX_IMAGES_PER_DOC` 刻意未调大（parser 与 server 共用，调大同时放大解析响应体）
- **知识图谱抽取吞吐优化（回补进 `docs/M16-CONTRACTS.md` §4.3）**：一万分片的库开启 GraphRAG 后
  "重新抽取"以小时计。根因是分批栅栏——每批等齐最慢的那次 LLM 调用才提交下一批，而 LLM 延迟
  长尾极重，线程池大半时间在批尾空转。改为无栅栏流水线（全部分片一次排队，谁空闲谁接下一个），
  并把一次抽取拆成"N 路并发调模型 + 单写入者串行落图"两段：图 schema 用复合索引而非唯一约束，
  并发 MERGE 同名实体会打架，原先"并发只能是 2"正是拿正确性换速度；拆开后模型调用只受限流约束，
  同一个库的图写入反而比原先更严格。`.env.example` **移除 `GRAPH_EXTRACT_BATCH_SIZE`**（它描述的
  机制已不存在；存量 `.env` 里留着不报错也不生效），`GRAPH_EXTRACT_CONCURRENCY` 默认 2 → 8。
  抽取任务同时移到独立线程池（core 1 / max 2）——它是全系统唯一以小时计的任务，此前与文档解析
  共用 core 2 / max 4 的索引池，两个全量抽取就能让文档上传排在一个明天才结束的活后面。
  **模型侧峰值并发 = 2 × `GRAPH_EXTRACT_CONCURRENCY`**（默认 16），限流额度紧的部署把后者调小即可，
  正确性不依赖它。
- **抽取限流改为退避重试（`docs/M16-CONTRACTS.md` §4.6，醒目提示：此前限流会静默丢片）**：
  把抽取并发调到 24（峰值 = 24 × `GRAPH_TASK_CONCURRENCY`= 48）后 DashScope 返回 429，而抽取原先把
  429 和"模型答歪了"同等对待——计入跳过、不重试。429 的语义是"稍后再试"且成片到来（额度一打穿，接下来
  几十个调用全是 429），所以一次抽取可能静默丢掉几百个分片，还把它们计进界面上写着「输出校验未通过」
  的那个数里。`.env.example` 新增 `GRAPH_EXTRACT_RETRY_ON_THROTTLE`（默认 3，填 0 恢复旧行为），
  指数退避 + 抖动（固定退避会让所有线程一起重试、复现触发 429 的突发），等待发生在抽取线程内部并占着
  并发槽位——限流时整次抽取自然降速到额度能承受的水平。只重试限流：鉴权失败/模型不存在/输入过长重试
  一次也是同样结果。结束日志新增 `throttleRetries=`，它是"该不该降 `GRAPH_EXTRACT_CONCURRENCY`"的
  唯一依据。**调并发前先看这个数**：撞限流的表现是失败率上升而不是变慢。
- **图谱抽取降延迟（`docs/M16-CONTRACTS.md` §4.6，醒目提示：同一分片抽出的实体/关系可能比升级前少）**：
  流水线化并把并发提到 12 之后，385 分片仍要 20 分钟，而并发从 2 提到 12（6 倍）几乎没变快——瓶颈不在并发。
  实测归因：Neo4j 写入每片约 50ms（385 片共 19 秒，占 1.6%）、热点实体度数最大 88，最终落到 LLM 调用
  37.4 秒/次，而用图里的实测均值精算 —— 16 实体 + 20 关系序列化约 1482 token，占 `max_tokens=2048` 的
  72%，qwen-plus 约 40 token/s → 37 秒，与反算吻合。**抽取延迟 ≈ 输出 token 数 ÷ 生成速度，与并发无关**，
  且均值就占 72% 预算、长尾必然溢出（实测 9% 的分片因 JSON 截断而整片丢失）。`.env.example` 新增
  `GRAPH_EXTRACT_MODEL`（留空 = 沿用 `CHAT_MODEL`；换 turbo 档生成速度翻倍以上，而抽取只是照固定 JSON
  形状填空，质量损失远小于用它做查询改写）与 `GRAPH_EXTRACT_MAX_ENTITIES`（默认 24，实体与关系共用上限）；
  **`GRAPH_EXTRACT_MAX_TOKENS` 默认 2048 → 3072**。提示词新增数量上限与紧凑 JSON 要求，换来的是不再有
  分片因截断整片丢失。另：图写入的 Chunk MERGE 补 `kb_id` 谓词以命中复合索引（原先退化为全 Chunk 扫描）。
  **运维注意**：抽取只覆盖任务启动那一刻已完成索引的分片——批量导入后应等索引全部完成再点「重新抽取」，
  否则界面上的覆盖分片数会远小于库里的分片总数，需要重跑。
- **并发参数按机器可调（`docs/M16-CONTRACTS.md` §4.5）**：`.env.example` 新增
  `INDEX_CONCURRENCY`（同时索引几个文档）、`GRAPH_TASK_CONCURRENCY`（几个知识库能同时重建图谱）、
  `PARSER_MAX_WORKERS`（解析服务工作线程数），默认值等于此前的硬编码值，**不设变量的部署行为不变**。
  文档里点明三个不随机器变大而变大的天花板：模型侧限流（撞上表现为任务失败率上升而非变慢）、
  `PARSER_MAX_WORKERS`（解析是真 CPU 密集且 uvicorn 单进程，调大调用侧只会把队列挪到 parser 门口）、
  `MYSQL_POOL_SIZE`（不同步扩就是把瓶颈换成 connection timeout），并给出 10 核 / 64GB 单机的一套
  参考值。**图谱任务池由实际恒为 1 变为 2**（同 §4.4 ③ 的队列陷阱），parser 侧 OCR 调用池由固定 2
  改为跟随 `PARSER_MAX_WORKERS`——它原本只是给单页 OCR 套超时的载体，却被所有 worker 共享，
  扫描件批量场景被卡在 2 页并发。
- **文档索引吞吐优化（回补进 `docs/M16-CONTRACTS.md` §4.4）**：①`.env.example` 新增
  `EMBEDDING_CONCURRENCY`（默认 4）——嵌入批次此前逐批串行，500 个分片按批大小 10 算就是 50 次
  网络往返、光嵌入要半分钟到两分钟，而批次之间毫无依赖；这是**全局**上限（共享线程池），几个文档
  同时索引也不会把嵌入服务限流打穿，填 1 即恢复串行行为。②嵌入状态由逐分片 UPDATE 改为按批一条
  语句（500 条降到 50 条）。③**同时索引的文档数由 2 变 4**：原索引池 `core=2/max=4/queue=200`，
  而线程池只在队列满后才扩容，200 深的队列让 `max=4` 永不可达、实际并发恒为 2——批量上传 50 个
  文件是两个两个处理的；改为 `core=max=4`。解析服务扛不住 4 路并发的部署需相应扩 parser 实例。

- **权限体系：功能权限 + 知识库数据权限 + 单点登录（M15）**：新增 `docs/M15-CONTRACTS.md`（开发契约，
  含 6 张表的数据模型、18 个权限码与 5 个内置角色矩阵、两层授权的执行点、单点登录行为与升级说明）；
  `.env.example` 新增权限/SSO 分节：`AUTH_LDAP_ENABLED`（默认 false，不配就是升级前的行为）、
  `AUTH_LDAP_URL`、`AUTH_LDAP_DOMAIN_SUFFIX`、`AUTH_LDAP_CONNECT_TIMEOUT_MS`、`AUTH_LDAP_READ_TIMEOUT_MS`、
  `AUTH_LDAP_DEFAULT_ROLE_CODE`（默认 `VIEWER`，目录账号首登自动建号时授予）。**升级提示**：Flyway
  `V16__rbac.sql` 会把存量账号（含 `admin`）全部提为 `SUPER_ADMIN`，否则升级后没人能进用户管理页
  发出第一个角色；未配置 LDAP 的部署登录页不会出现单点登录 Tab。
- OpenAPI 升至 `0.15.0-m15`：新增 `/api/v1/auth/sso-available`（免认证）、`/api/v1/users` 与
  `/api/v1/roles` 两组端点与相应 schema；`LoginRequest` 增 `mode`（LOCAL/SSO，缺省读作 LOCAL），
  `/auth/me` 返回体补角色/权限/库范围；`ErrorCode` 增 `FORBIDDEN`，新增 `PermissionDenied` 403 响应。

- **三期第一批：连接器 / 元数据抽取 / 切分扩展 / 混合重排 / 多模态索引 / 以图搜图（M14，docs/M14-CONTRACTS.md）**：
  ①外部数据源连接器——连接器 SPI（`ExternalConnector`/`ConnectorRouter`，为后续 Confluence/飞书预留）+
  首个实现 S3/OSS 兼容对象存储；`POST/GET /kb/{kbId}/ext-sources` 登记/列表、`POST
  /ext-sources/{id}/sync` 手动异步同步、`GET /ext-sources/{id}/items` 对象明细、
  `PUT`/`POST .../test`/`DELETE /ext-sources/{id}` 更新/连通性测试/移除；按 prefix 扫描桶内对象
  走既有上传管线入库（派生稳定文件名，ETag 未变→UNCHANGED 不重传、变了→新版本），源与文档
  弱绑定——移除登记或对象消失均不删文档（SKIPPED）；两张新表 `t_kb_ext_source`/
  `t_kb_ext_source_item`（Flyway V15）；②配置化元数据抽取——知识库级 `metadata_rules`
  （constant/regex/keyword_match 三类，≤10 条），切分后逐 chunk 抽取入 metadata 并以 `ext_`
  前缀镜像引擎，检索侧 `metadata_filter.custom` 等值过滤；规则计入 chunk 指纹；③切分策略扩展——
  新增 `separator`（分隔符/正则）、`heading`（markdown 标题层级）、`page`（解析页边界）三个
  TextSplitter 策略，超长段统一回落 fixed_length，与父子分片互斥（组合报 INVALID_PARAM）；
  ④Rerank 混合模式——`rerank_mode=hybrid` 将语义重排分与归一化 BM25 分线性加权（`rerank_w_semantic`
  默认 0.7）决定排序，仅影响排序、不改变 `score_threshold` 的绝对阈值语义，rerank 降级链路照旧；
  ⑤视觉理解整页索引——知识库级 `multimodal_enabled` 开关，开启且多模态 provider 配置时对 IMAGE
  类 chunk（内嵌图/独立上传图/扫描页整页渲染）额外产多模态向量（DashScope multimodal-embedding-v1，
  1024 维，物理索引 `{kbId}_mm`），检索新增第三召回路进 RRF 融合（图文同空间，文本可命中图），
  VLM 文本代理链路保留不变；⑥以图搜图入口——管理台检索调试页 `SearchRequest.images`（裸 base64，
  ≤3 张/单张 5MB/总量 10MB），multimodal 开启时图片直接嵌入多模态空间检索、否则回落 VLM 转写。
  web：知识库详情新增「外部数据源」Tab、索引配置抽屉增切分条件字段/元数据抽取分组/多模态开关、
  检索调试页增重排模式选择与图片上传；新增 `EXT_SOURCE_SYNC_CRON`/`EXT_SOURCE_SYNC_ENABLED`/
  `EXT_SOURCE_SYNC_BATCH_SIZE`/`EXT_SOURCE_MAX_OBJECTS_PER_SOURCE`/`EXT_SOURCE_FETCH_TIMEOUT_MS`、
  `MULTIMODAL_EMBEDDING_MODEL`/`MULTIMODAL_EMBEDDING_API_KEY`/`MULTIMODAL_EMBEDDING_DIM`/
  `MULTIMODAL_EMBEDDING_URL`/`MULTIMODAL_EMBEDDING_TIMEOUT_MS`、`RETRIEVAL_RERANK_MODE`/
  `RETRIEVAL_RERANK_W_SEMANTIC`；`degraded` 枚举新增 `mm_route_unavailable`（多模态检索不可用降级）/
  `mm_route_skipped`（加权融合下多模态路跳过）；OpenAPI 升 0.14.0-m14。纯新增表/端点/配置键，
  存量行为零变化——新 JSON 配置字段缺省即现状（`metadata_rules` 空、`split_strategy` 旧值不变、
  `rerank_mode=semantic`、`multimodal_enabled=false`）；`MULTIMODAL_EMBEDDING_API_KEY` 留空则
  继承 `DASHSCOPE_API_KEY`，两者皆空 = 多模态能力整体关闭

### Security

- **外部数据源凭证明文存储（M14，醒目声明）**：`t_kb_ext_source.secret_key` 明文落库，读 API
  恒返回 `******`、更新时传空 = 保留旧值。取舍前提与 D17 一致——管理台单管理员 + 网络隔离；
  引入 KMS/信封加密属权限体系批次，不在本期范围。部署侧务必保证数据库与网络访问隔离

- **运维可观测：Prometheus 业务指标（M13，docs/M13-CONTRACTS.md）**：kb-rag-server 自本版起
  可被 Prometheus 抓取——补齐 `micrometer-registry-prometheus` 依赖激活既有 actuator 的
  `/actuator/prometheus` 端点（JVM/HTTP 基础指标随 actuator 自动配置免费提供）；新增业务
  指标：`kb_search_seconds`（Timer，标签 source=console/open_api、zero_hit、degraded，
  preview 管理流量不计入）、`kb_task_completed_total`（Counter，type × success/failed）、
  `kb_task_backlog`（Gauge，pending/running 抓取时实查 DB，DB 异常返回 NaN 不影响抓取）、
  `kb_openapi_rejected_total`（Counter，按 error_code）、`kb_websource_sync_total`
  （Counter，M12 四态）。纯新增：无表变更、无新环境变量、无端点行为变化；该端点与
  health 同口径暂无鉴权，生产由部署侧网络隔离
- **数据接入：URL 导入与增量同步（M12，docs/M12-CONTRACTS.md）**：①网页登记——
  `POST/GET /kb/{kbId}/web-sources` 登记即抓/列表，`POST /web-sources/{id}/sync` 手动同步，
  `PUT`/`DELETE /web-sources/{id}` 定时同步开关与移除登记；抓取产物统一走文档上传
  管线（URL 派生稳定文件名，重抓同名加版本、content_hash 去重），登记与文档为弱绑定——
  移除登记不删文档；②增量同步四态结果（SUCCESS/UNCHANGED/SKIPPED/FAILED）落行可见，
  内容 hash 未变不建版本，绑定文档在回收站则跳过；③SSRF 防线：仅 http/https、拒内网/
  回环/链路本地地址，重定向手动跟随且逐跳复验；④parser 新增通用 HTML 页面解析器
  （非聊天记录通道）；web 知识库详情新增「网页导入」Tab；新增 `WEB_IMPORT_FETCH_TIMEOUT_MS` /
  `WEB_IMPORT_MAX_PAGE_SIZE_MB` / `WEB_IMPORT_MAX_REDIRECTS` / `WEB_IMPORT_SYNC_CRON` /
  `WEB_IMPORT_SYNC_ENABLED` / `WEB_IMPORT_SYNC_BATCH_SIZE`；OpenAPI 升 0.13.0-m12
- **内容治理（M11，docs/M11-CONTRACTS.md）**：①审核发布——知识库级 `review_required`
  开关（`PUT /kb/{kbId}/governance`），开启后新上传文档初始为 DRAFT，经
  submit-review/approve/reject 状态机（DRAFT|REJECTED → PENDING_REVIEW →
  PUBLISHED|REJECTED）发布后才参与检索；②文档有效期——`PUT /documents/{docId}/validity`
  设置/清除 `effective_at`/`expires_at` 窗口，仅窗口内参与检索，expires_at 设为过去即
  立即下架；③回收站——trash 列表/restore/purge 端点，超过保留期由定时任务自动清除；
  治理三态均收敛为检索时的活跃集 DB 谓词过滤，不写引擎，状态变更即时生效（时间窗
  穿越靠缓存 TTL 5 分钟内收敛）；web 知识库详情新增发布状态/有效期列、审核操作、
  「回收站」Tab 与审核开关；新增 `TRASH_RETENTION_DAYS` / `TRASH_PURGE_BATCH_SIZE` /
  `TRASH_PURGE_CRON` / `TRASH_PURGE_ENABLED`；OpenAPI 升 0.12.0-m11；存量文档升级后
  默认 PUBLISHED/无有效期/不在回收站，检索结果与升级前一致；已发布应用快照不受
  治理影响

### Changed

- **上传白名单默认值变更（M12，醒目提示）**：`UPLOAD_ALLOWED_EXTENSIONS` 默认值新增
  `html`（网页抓取产物走上传管线的前提）。显式设置过该变量的部署需自行追加 `html`，
  否则 URL 导入首次同步即报“不支持的文件类型”；html 无魔数，与 txt/md/csv 同样仅验
  扩展名与大小
- **`DELETE /api/v1/documents/{docId}` 语义变更（M11，醒目提示）**：URL 不变，但删除
  由不可逆改为移入回收站（检索立即下线、数据保留、保留期内可 `POST
  /documents/{docId}/restore` 还原）；原来的不可逆删除（含两个检索引擎副本）迁移至
  `DELETE /documents/{docId}/purge`，且仅对回收站内文档有效（两段式防误删）。依赖旧
  语义的调用方需改为先 DELETE 再 purge

### Added

- **检索质量闭环（M10，docs/M10-CONTRACTS.md）**：①检索反馈从 log-only 升级为持久化闭环——
  `POST /retrieval-feedback` payload 不变（兼容红线）但落库为可管理行，新增知识库维度的反馈
  列表与转评测集（case source=FEEDBACK）/忽略端点，web 知识库详情新增「反馈管理」Tab；
  ②检索洞察：控制台调试与 OpenAPI 检索自动记录脱敏摘要/命中数/降级标记（评测运行不记录，
  不存原文），新增明细分页与内容缺口报表端点（零命中率/Top 未命中 query 归一化分组），
  web 新增「检索洞察」Tab；新增 `INSIGHT_ENABLED` / `INSIGHT_RETENTION_DAYS` /
  `INSIGHT_CLEANUP_BATCH_SIZE` / `INSIGHT_CLEANUP_CRON`；OpenAPI 升 0.11.0-m10

### Changed

- **full 模式向量引擎定为 Qdrant（不兼容变更）**：`VECTOR_ENGINE` 合法取值为 `es | qdrant`。
  compose 中 full 模式在 lite 之上只叠加单个 `qdrant` 容器（自带存储，无需额外的元数据服务或
  对象存储），内存档 16GB；对应的 `.env` 变量为 `QDRANT_URI` / `QDRANT_API_KEY` /
  `QDRANT_PORT` / `QDRANT_GRPC_PORT`。lite 模式（`VECTOR_ENGINE=es`）不受影响。
  切换向量引擎时向量数据无法原地搬迁，需清理索引台账后按 [UPGRADING.md](UPGRADING.md)
  「ES / Qdrant：schema 变更走"从事实源重建 + 别名切换"」从 MySQL 事实源重建

### Fixed
- 两处文档滞后于 M8 交付的修正：mappings/README.md「已知限制」仍写 TXT/HTML 降级二期，更新为
  M8 已交付（内置 liuhen_txt/liuhen_html 模板、自定义正则/选择器）并补充映射档案 CRUD 界面与
  t_kb_source_mapping 的指引；NOTICE 的 PaddleOCR 条目从"planned, not used"更新为 M8 已集成的
  可选依赖（requirements-ocr.txt、OCR_ENGINE 开关、模型权重不随仓分发），并同步修正 qwen-vl
  条目中"no local OCR engine involved"的过期交叉引用
- fusion 字段两处形状错位（kb-rag-web PR#14，用户实测报告）：①评测估算/提交把 fusion 发成
  {mode,rrf_k} 对象而 server 是字符串字面量，勾选混合检索/混合+重排即 Jackson 500；②应用配置页
  读写嵌套 retrieval.fusion 对象而 server 快照是扁平 fusion_mode/w_vec/rrf_k，未知字段被静默丢弃
  ——应用的融合设置端到端从未生效。两处对齐真实形状；管理端调试 search 两侧本就一致未动
- 评测报告与门禁双跑对比全列 NaN%（kb-rag-web PR#13，用户实测截图报告）：M4b 期 web 类型层把每个
  指标假设为 {value, ci_low, ci_high} 对象且该 ASSUMPTION 从未与后端定版核对，后端实际返回扁平
  数字 + 独立 recall_ci/hit_rate_ci —— 数字通过空值检查、.value 取出 undefined → NaN%；两个抽屉
  与 CSV 导出一并修正。伴生发现并修复 server 真实指标缺陷（kb-rag-server PR#17）：重叠切分让同一
  证据 span 命中多个候选时 IDCG 仍按声明证据数归一，NDCG 实测 2.948>1；理想相关数改取
  max(声明, 观测) 截断到 K
- 真流式从未生效（kb-rag-server PR#16，用户配有效 Key 首次真跑流式暴露）：chat-preview 与对外
  /knowledge/chat 把 SseEmitter 藏在 ResponseEntity<?> 后返回，Spring 按声明类型选返回值处理器、
  emitter 被交给消息转换器 → HttpMessageNotWritableException 500；修复为 produces=text/event-stream
  拆分独立流式方法，Accept 与 stream 字段错配报可操作 400（原"json Accept 也给流"承诺不可实现已移除，
  见 M4c-CONTRACTS.md §6 补记）。同 PR 新增 CHAT_GENERATE_TIMEOUT_MS（默认 60s）——生成与
  路由/改写共用 3s 预算导致真实生成必超时，现仅生成读上限独立放宽
- SSE 流式端点（chat/chat-preview）上抛出的业务异常被内容协商吃成裸 500：Accept 仅为
  text/event-stream 时 JSON 错误信封无法协商渲染，过期 token 的 401 语义被掩盖为
  Internal Server Error；修复为对 stream-only Accept 手写 JSON 信封绕过协商
  （kb-rag-server PR#15，用户实测发现）

### Added
- 开源发布就绪：README 通读全文并核对滞后——状态行由"M4b 里程碑"更新为"一期
  （M1-M7）已完成、二期进行中（M8 已完成、M9 开发中）"；补齐此前缺失的对应章节
  （多知识库路由 M5、应用发布与索引快照回滚 M6、GraphRAG 知识图谱 M7 可选启用、
  聊天记录 TXT/HTML 格式与映射档案维护 M8）；"总体文档"导航补
  `docs/ARCHITECTURE.md`/`docs/FLOWS.md` 链接；修正两处滞后表述（"对外 API Key
  开放平台网关是后续里程碑 M4c 范畴"已随 M4c 交付、"开源工程文档"小节里概括
  NOTICE 内容的 PaddleOCR 一词由"预留"改为如实反映 M8 起已是可选依赖，NOTICE.md
  正文本身留待独立的许可合规复核）；性能数字统一为已验收口径（M2 基础链路 P95<2s /
  完整链路含改写 P95<3s，M6 十万分片压测 P95 劣化 15.9%≤20%，出处 M2/M6-CONTRACTS.md）。
  新增 `.github/workflows/ci.yml`：push/PR 触发校验 `docker compose config -q`
  （lite/full 及各自叠加 es-ik override 四组合）、`docs/openapi/*.yaml` 只读
  `yaml.safe_load` 语法校验（不改动 openapi 内容）、`scripts/*.sh` 的 `bash -n`
  语法校验。新增 `UPGRADING.md`：compose 镜像 tag 固定原则、MySQL 走 Flyway
  自动迁移（禁手工 DDL、向后兼容一版、不可跨版本跳升）、ES/Qdrant schema 变更走
  "从事实源重建 + 别名切换"、升级前先跑 `scripts/backup.sh`、CHANGELOG 条目如何
  标注 schema 变更，对应需求文档 §5"升级与迁移"条款
- M9 标注语义与图搜（docs/M9-CONTRACTS.md，二期收官批=清单项 5/6/7，至此二期 1-7 全部交付）：
  父片精确剔除（t_kb_chunk 落 parent_start/end_offset V10——切分副产物+截取一致性校验；禁用子片
  按偏移倒序剔除并以「（已省略被禁用内容）」替换、metadata 带 redacted_child_count；任一无偏移
  整片回退；子编辑/合并拆分/父编辑三路失效单点）；标注相似度辅助迁移（对称 Dice 字符 3-gram、
  同文档候选 top3 阈值 0.35、只推荐+migrate 幂等端点、不自动不批量）；图片 query（images 仅
  base64 ≤3张/5MB/10MB，VLM 转文本前缀拼接在改写之前，degraded=image_understanding_unavailable，
  纯图理解失败 INVALID_PARAM）；OpenAPI 升 0.10.0-m9、需求文档升 v1.15
- M8 导入与解析增强（docs/M8-CONTRACTS.md，二期第一批=二期清单项 1/2/3/4）：聊天记录 TXT/HTML
  两种新格式（TXT 内置留痕/微信 PC 双行模板、HTML 内置留痕选择器模板，均按公开约定编写待真实样例
  校准；行首正则命名捕获组与 DOM 选择器随 mapping profile 承载可自定义；不匹配行>30% 报可操作错误）；
  PaddleOCR 本地兜底（parser 可选依赖 requirements-ocr.txt，OCR_ENGINE=paddle 三级次序
  VLM→本地 OCR→跳过降级，未装依赖启动 fast-fail；实测校准为 paddlepaddle 3.3.1 + paddleocr 3.3.3
  的 3.x API）；聊天聚合重叠滑窗（window_overlap，默认 0 全兼容）与检索侧近重复归并（同会话
  msg_span 重叠率≥0.5 留最高分、merged_window_chunk_ids 留痕）；字段映射维护界面
  （t_kb_source_mapping 建表 V9+内置模板种子化+CRUD/复制端点+系统设置 tab）；OpenAPI 升 0.9.0-m8
- M8 期间修复两个遗留缺陷：①（M7）NEO4J_URI 为空时 Spring Boot Neo4j 自动配置默认连
  localhost:7687 并注册健康探测致整体 DOWN，破坏"空 URI 零影响"契约——排除该自动配置；
  ②（既有）UpdateIndexConfigRequest 的清洗与聊天聚合校验被父子分片 early-return 短路、
  单层库从未生效——校验上移为无条件执行
- M7 GraphRAG（docs/M7-CONTRACTS.md，一期收官）：知识库级实体/关系抽取（逐分片 LLM 抽取 JSON、
  注入防护分隔声明、输出强校验跳过计数落库 `t_kb_task.skipped_count`）入 Neo4j（`(:Entity)-[:REL]->
  (:Entity)`、`(:Entity)-[:MENTIONED_IN]->(:Chunk)` 溯源边，Neo4j 为可从 MySQL 重建的派生存储）；
  图检索路作为库内第三路进 RRF——query 轻量切词后经实体名 fulltext（cjk 分析器）匹配、N 跳扩展、
  溯源回 chunk，关联度=匹配分/(1+跳数)，回溯 chunk 复用 MySQL 事实源过滤谓词二次校验（版本可见集+
  未禁用），版本隔离不被图路击穿；开启图路的库库内融合强制 RRF（与 weighted 互斥校验单点）；
  Neo4j 未配置/不可达降级 `degraded=graph_route_unavailable`、快照上下文图路直接关闭；文档/知识库
  删除级联清理图数据（溯源边+孤立实体）；管理端五端点（config/extract/summary/entities/entity
  chunks）与知识图谱页（简版 SVG 力导向可视化）；Neo4j 5 以 compose profile=graph 可选启用
  （默认不启动，保 lite 4GB 承诺）；OpenAPI 升 0.8.0-m7、需求文档升 v1.13
- M7 验收中发现并修复 M6 遗留缺陷：分片禁用广播对已缺失的快照索引执行 ES bulk update 会触发
  `action.auto_create_index` 把该名字重建为空索引，使 `snapshot_index_missing` 降级安全网被静默
  击穿（RELEASED 调用查询空快照返回空结果且无降级标记）；修复为广播前 `indexExists` 探测、缺失
  跳过并 error 日志，附回归单测
- M6 索引快照发布（docs/M6-CONTRACTS.md）：发布门禁通过/force 之后、状态切 RELEASED 之前，对关联
  知识库的物理索引执行不可变快照（ES `_clone`：源索引临时置 `index.blocks.write=true` → clone →
  两端解锁，段级硬链接毫秒级完成；Qdrant 为同步批量读写拷贝）并固化当时的版本可见集
  （`visible_version_ids`）与快照索引清单（`index_snapshots`），任一库快照失败则发布中止、已建
  的本次快照回滚删除、版本停留在门禁结论状态可重试；经 RELEASED 版本（含 rollback 重新发布的
  历史版本）发起的对外调用固定检索这份快照与固化可见集，回滚即刻恢复历史知识状态，TESTING 灰度/
  chat-preview/管理台调试/评测仍走实时别名与当前激活集合；快照索引被误删时降级为实时别名并记
  `degraded=snapshot_index_missing`（M6 之前发布的旧 RELEASED 版本走同样路径但不记该标记，属历史
  数据形态而非故障）；`AppVersionPinChecker` 落地归档保护——文档版本被任意未清理应用版本（含
  SUPERSEDED）的固化可见集引用即 pin，`VersionRetentionService` 跳过之；新增按应用保留最近 3 个
  SUPERSEDED 版本快照的定时清理任务，超出的删物理索引并解除 pin，RELEASED 快照永不清理；
  `scripts/backup.sh`/`scripts/restore.sh`（mysqldump + ES 数据导出/mc mirror + MinIO 全量，
  产物带时间戳目录）与 `docs/backup-restore.md`（RPO/RTO 说明与演练步骤）；`scripts/seed-bench.py`
  零 Key 直写 10 万分片压测数据，P95 由 33.7ms 劣化至 39.0ms（+15.9%，≤20% 验收阈值内）。
  `docs/openapi/kb-server.yaml` 同步：`AppVersionResponse` 增 `index_snapshots`/
  `visible_version_kb_count`，`DocumentVersionResponse` 增 `pinned`/`pinned_by`，`degraded`
  枚举增 `snapshot_index_missing`（并补齐 M5 遗漏的 `route_fallback_all`），`info.version` 升至
  `0.7.0-m6`。
- M5 多知识库路由（docs/M5-CONTRACTS.md）：应用版本配置 `kb_id` 单库字段废弃为兼容可选项，
  新增 `kb_refs`（1..15 个知识库 + 配额权重，正整数，默认 1，`kb.retrieval.max-linked-kb`
  控制上限）；`RoutingService` 按需（路由开关开启且应用挂 ≥2 库时）调用 ChatProvider 做
  LLM 选库，输出与候选知识库白名单求交集，空交集/解析失败/超时/未配置对话模型一律降级为
  检索全部关联库并记 `degraded=route_fallback_all`（需求文档 §4.4 注入防护③），决策结果按
  query+候选集哈希缓存（`kb.retrieval.routing-cache-ttl-minutes`/`routing-cache-max-size`）；
  跨库检索基于库内排名做 Reciprocal Rank Fusion 合并（`CrossKbRrfFusion`），rerank 候选总
  预算（全局默认 50，非每库）按 `kb_refs` 权重比例分配到各库（`KbQuotaAllocator`，向下取整、
  余量归权重最高库，验收用例权重 3:1 分 50 得 38/12）；对外/管理 search、chat、chat-preview
  响应新增 `routed_kb_ids`（`applied` 信息条或顶层，SSE `done` 事件同增）与
  `RetrievalNode.metadata.kb_id`；门禁评测集绑定放宽为「所属知识库属于版本 kb_refs 并集」；
  旧版仅存单 `kb_id` 的快照读侧兼容翻译，无需迁移。`docs/openapi/kb-server.yaml` 同步全部
  M5 字段变更，`info.version` 升至 `0.6.0-m5`。
- 补齐 M4c OpenAPI 欠账（`docs/openapi/kb-server.yaml`，`info.version` 升至 `0.5.0-m4c`）：
  应用与版本全部端点（CRUD/`versions`/`gate-dataset`/`submit-test`/`release?force`/
  `rollback`）、控制台 `chat-preview`（JSON + SSE）、API Key 管理（`create`/`list`/`status`/
  `scope`/`rotate`/`delete`）、调用审计查询与统计（`/api-audit-logs`、`.../stats`）、对外
  `/api/v1/knowledge/search`、`/chat`（新增 `ApiKeyBearer` securityScheme 区分管理鉴权）、
  `AppVersionStatus` 八状态机、`GateVerdict`/`GateReason` 枚举、SSE 事件 schema 与
  `APP_ACCESS_DENIED`/`API_KEY_DISABLED`/`RATE_LIMITED` 等错误码，此前该增量因排期滞后于
  server 侧实现（M4c-CONTRACTS.md §6 已记录该欠账）。
- M4c 应用发布与开放能力：应用与版本八状态机（单应用唯一 RELEASED）、发布门禁（同语料双跑/容差 ε/有效 case 交集/四情形 LOG_ONLY/force 留痕/首发基线）、对外 knowledge search+chat（API Key 哈希鉴权、app_scope、令牌桶限流、SSE 流式、注入防护 prompt）、API Key 管理、审计落库与 180 天归档、Flyway V6。
- M4b 评测体系（docs/M4b-CONTRACTS.md）：评测集/case 的增删改查与分页、证据复核工作台
  （待复核 case 列表 + Top3 候选原文 + REANCHOR/DEPRECATE）、检索调试页一键收进评测集与
  检索结果反馈标注、Demo 示例评测集导入（按 file_name+content_hash_sha256 匹配库内文档、
  幂等）、评测运行配置矩阵（BM25_ONLY/VECTOR_ONLY/HYBRID/HYBRID_RERANK 一次提交产生 N 个
  run）与提交前费用预估、run 详情/命中明细下钻/同 dataset_revision 下的多 run 对比、
  三层嵌套指标（overall/span/document/single_turn/multi_turn 分组 × Recall/Precision/
  Hit Rate/MRR/NDCG + Wilson 95% 置信区间）；`docs/openapi/kb-server.yaml` 同步全部
  端点与 schema（枚举 AnchorType/CaseStatus/RunStatus/EvalMode 齐全），`ActivateImpact.
  affected_eval_case_count` 由 M4a 恒 0 占位改为真实统计说明；`.env.example` 新增
  `EVAL_JUDGE_MODEL`/`EVAL_OFFLINE_TIMEOUT_MS`/`EVAL_CONCURRENCY`/`EVAL_OVERLAP_THRESHOLD`/
  `EVAL_DEGRADED_RETRY`。
- M4a 文档版本与分片标注：同名文件重复上传按 major/minor 规则生成新版本（内容全同则不建版）、版本列表与激活/影响预检端点、即时回退与归档版本重建回退（rollback_mode）、非激活版本保留策略与归档清理、分片标注四操作（编辑/启禁用/合并/拆分，统一走事实源先行再双引擎同步）、父子分片禁用语义（disabled_child_ids 与 hide_parent_with_disabled_child 开关）、标注跨版本按 chunk_text_hash 精确继承与待复核清单、Flyway V4 建 t_kb_annotation。

- （M3）`demo/`：4 篇原创 RAG/知识库技术说明文档（覆盖 pdf/docx/xlsx/md 各一，中文，
  各篇 300-800 字）+ `demo/manifest.json`（文件名/标题/说明/建议 query 列表）+
  `demo/eval-cases.json`（10 条示例评测集，span 锚定证据摘录 + 关联文件名，结构对应
  需求文档 §6 t_kb_eval_case；本期只分发，导入功能排期 M4b）+ `demo/tools/generate_demo_docs.py`
  （docx/pdf/xlsx 可复现生成脚本，pdf 内嵌一张自绘流水线示意图用于验证 VLM 图片解析
  链路）+ `demo/README.md`
- （M3）`mappings/chat/memotrace.yml`：微信「留痕」/MemoTrace 聊天记录列名映射模板
  （对应 kb-rag-parser `POST /api/v1/parse/chat` 的 `mapping_profile` 默认档案，见
  M3-CONTRACTS.md §2.2）+ `mappings/README.md`（如何为新来源新增映射档案）
- （M3）`.env.example` 新增 `VISION_MODEL`/`VISION_TIMEOUT_MS`/
  `SCANNED_PAGE_TEXT_THRESHOLD`/`MAX_IMAGES_PER_DOC`/`DEMO_DATA_DIR`
- （M3）`docs/openapi/kb-parser.yaml` 同步 M3-CONTRACTS.md §2：`/api/v1/parse` 响应
  新增 `pages[].scanned`、`data.images[]`（`kind=embedded|page_render`）、
  `data.warnings[]`；新增 `POST /api/v1/parse/chat` 聊天记录（CSV/Excel）解析端点
- （M3）`docs/openapi/kb-server.yaml` 同步 M3-CONTRACTS.md §3：`model-status` 新增
  `vision_configured`/`vision_provider`/`vision_model`；新增解析预览与确认
  （`GET /api/v1/documents/{docId}/preview`、`POST .../confirm`、`POST .../reparse`、
  `POST /api/v1/kb/{kbId}/documents/confirm` 批量确认，`ProcessStatus` 增
  `PENDING_CONFIRM`）；新增聊天记录导入（`POST /api/v1/kb/{kbId}/chat-imports` 匹配预览
  + `.../confirm` 执行导入）；新增告警配置（`GET|PUT /api/v1/system/alert-config`、
  `POST .../test`）；新增 Demo 一键导入（`POST /api/v1/system/demo/import`、
  `GET /api/v1/system/demo/status`）；`IndexConfig` 新增 `clean_rules`/
  `parse_preview_required`/`chat_aggregation`
- （M3）`NOTICE` 增 DashScope qwen-vl-max（图片理解/OCR，M3-CONTRACTS.md §3.1）使用声明，
  并说明 PaddleOCR 在 M1-M3 均未引入（本地 OCR 兜底二期再评估，M3 扫描件 OCR 由 qwen-vl 承担）
- （M2）`es-ik/Dockerfile`：基于 `docker.elastic.co/elasticsearch/elasticsearch:8.11.4`
  安装 analysis-ik 插件（infinilabs 官方发布 zip，`IK_VERSION` 构建参数化，默认
  `8.11.4`）+ `docker-compose.es-ik.yml` override（build 该镜像替换 elasticsearch
  服务、挂载 `es-ik/config/IKAnalyzer.cfg.xml` 对接 kb-rag-server 词典热更新通道、
  Linux 下用 `extra_hosts: host.docker.internal:host-gateway` 补齐 macOS Docker
  Desktop 自带的 host 域名解析），README 增补「启用 ik」章节（M2-CONTRACTS.md §3）
- （M2）`scripts/benchmark.sh`：对指定知识库并发跑检索压测（`BASE_URL`/`TOKEN`/
  `KB_ID`/`QUERY_FILE`/`TOTAL`/`CONCURRENCY` 均可配置，默认内置 10 条中文查询、
  200 次、并发 5），纯 bash + curl + awk + sort 实现（不引入 jq/python 依赖），
  输出 P50/P95/P99 与错误数（含连接失败 `000` 的清晰提示），对应验收口径
  M2-CONTRACTS.md §7「基础链路 P95<2s」
- （M2）`docs/openapi/kb-server.yaml` 同步 M2-CONTRACTS.md §1.5/§3/§4 契约：search
  新入参（score_threshold/fusion/rerank_enabled/rewrite_enabled/messages/
  metadata_filter）与出参（`applied` 信息条、`RetrievalNode.metadata` 新增各路
  归一化分/rerank 分/child_ids）、`score_type`/`degraded` 枚举扩展、新增
  ik 词典 CRUD（`/api/v1/dict/ik`、`/api/v1/dict/ik/{dictId}`）与索引配置/重建
  端点（`PUT /api/v1/kb/{kbId}/index-config`、`POST /api/v1/kb/{kbId}/rebuild`）
- `docker-compose.lite.yml`：轻量模式中间件编排（MySQL 8.0 + Elasticsearch 8.11.4 单节点
  关闭安全模块 + MinIO），全部服务带 healthcheck / restart: unless-stopped / 固定镜像 tag /
  命名 volume
- `docker-compose.yml`：完整模式编排，在 lite 基础上（通过 Compose `include` 复用，避免
  重复维护）叠加 Qdrant 1.18.x（单容器自带存储，无需额外元数据服务或对象存储，与应用侧 MinIO
  隔离）与 Redis 7.2.x（`--profile redis` 显式开启，标注 optional）
- `.env.example`：契约 §1 全部环境变量 + docker-compose 专用变量，中文注释标注零 Key 模式
  下可空的变量
- `scripts/preflight.sh`：部署前置检查（docker/内存/端口占用/占位口令检测）
- `scripts/backup.sh`：MySQL 全量 mysqldump + MinIO 数据卷全量导出，按份数轮转
- `docs/openapi/kb-server.yaml`、`docs/openapi/kb-parser.yaml`：M1 端点 OpenAPI 3.0 契约
  （含 RetrievalNode、统一错误响应、degraded 枚举）
- 开源工程基线文件：LICENSE (Apache-2.0)、NOTICE（MySQL/ES/Qdrant/MinIO/Redis/MinerU
  许可声明）、SECURITY.md、CONTRIBUTING.md、Issue/PR 模板

### Notes

- 2026-07-26：M6-CONTRACTS.md §4 验收通过（零 Key 域）——V1 发布产生快照
  `kb_{id}_none_s1`（ES 实索引 + registry 行 + 两列固化），新文档版本激活后对外 search（V1）
  不含新内容、管理台调试含新内容（快照隔离实证）；V2 发布 s2 后 rollback V1，检索恢复历史状态且
  召回非空；旧文档版本 `pinned=true` 且 `pinned_by` 指向引用它的应用版本；误删快照后 RELEASED
  调用 `degraded=[snapshot_index_missing,…]` 且结果出自实时索引；M5 期旧格式 RELEASED 兼容调用
  不记该标记；备份-删库-恢复演练（`backup.sh` → `DROP DATABASE` + 删全部 `kb_*` 索引 →
  `restore.sh` 恢复 301 行 chunk/15 索引 → 检索命中非空）；seed 10 万分片压测
  P50=18.3/P95=39.0/P99=159.8ms，对比 100 分片基线 P50=19.9/P95=33.7/P99=128.8ms，
  **P95 劣化 15.9% ≤ 20% 验收阈值**，200/200 全 2xx。单测 606 项（新增 53）全过。Key 恢复后
  补验：向量路快照检索、Qdrant 快照（需 full 模式）。本仓库范围内本次同步完成 M6 OpenAPI
  增量（`docs/openapi/kb-server.yaml` → `0.7.0-m6`）；`t_kb_app_version` 新增
  `visible_version_ids`/`index_snapshots` 两列的 Flyway V7 迁移脚本在 kb-rag-server 仓库，
  不在本仓库交付范围；compose/`.env.example`/需求文档 v1.12 回补由主会话另行处理
- 2026-07-26：M5-CONTRACTS.md §5 验收通过（零 Key 域）——双库路由关时两库都查且
  `node.metadata.kb_id` 覆盖两库；路由开 + 零 Key 时 `degraded` 含 `route_fallback_all`
  仍全库检索；权重 3:1 配额实测 `quotas={38, 12}`；M4c 旧版单库快照对外调用仍正常且
  `routed_kb_ids=[该库]`（读侧兼容）。单测 553 项（新增 53）全过；LLM 真实选库与 rerank
  参与的跨库排序待模型 Key 恢复后补验。本仓库范围内本次同步完成 M4c OpenAPI 欠账补齐 +
  M5 OpenAPI 增量，`t_kb_app_version.config` 的 JSON 结构变更（`kb_refs`/`routing`）无新增
  Flyway 迁移（沿用既有 JSON 列，读侧翻译兼容），迁移脚本本就在 kb-rag-server 仓库
  不在本仓库交付范围
- 本版本对应需求文档 v1.11 / M3-CONTRACTS.md 的 M3 里程碑增量（本仓库范围：Demo 文档集
  与生成脚本、示例评测集、聊天记录列名映射模板、`.env.example` 新增变量、OpenAPI 契约
  同步、NOTICE 声明）；`t_kb_image_asset` 表、`process_status` 增 `PENDING_CONFIRM` 等
  Flyway V3 迁移脚本在 kb-rag-server 仓库，不在本仓库交付范围。M3 只做 Demo **文档集**
  一键导入，示例评测集导入功能排期 M4b（需求文档同版本已同步修订 §10 M3/M4b 两行）
- 本版本对应需求文档 v1.8 / M2-CONTRACTS.md v1.0 的 M2 里程碑增量（本仓库范围：
  es-ik 镜像与 compose override、benchmark 压测脚本、OpenAPI 契约同步）；
  `t_kb_ik_dict`/`retrieval_config` 等 Flyway V2 迁移脚本在 kb-rag-server 仓库，
  不在本仓库交付范围
- 本版本对应需求文档 v1.8 / M1-CONTRACTS.md v1.0 的 M1 里程碑交付
- 不含 schema 变更（本仓库不承载数据库 migration，Flyway 脚本在 kb-rag-server 仓库）
