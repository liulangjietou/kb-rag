# M16 开发契约（企业化：多租户 + 文档级数据权限 + SSO 三协议 + 组同步 + 操作审计 · 增量于 M1-M15 契约）

> 需求依据：M15 契约 §0"本期不做"清单全部落地 + 需求文档 §13.4 遗留待办（deploy 事实源回补、M14 收口）+ M15 升级说明第 1 条（SUPER_ADMIN 收敛）+ `KnowledgeBaseService` 的 TODO(M4)（删库物理索引清理）。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带上下文、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；面向用户文案中文；web 枚举展示走 META 表。
> 用户已确认的三项选型：①SSO 协议 = **OIDC + SAML + CAS 三个全做**；②多租户 = **完整租户隔离**（tenant_id 贯穿业务表与 ES/Qdrant 索引命名）；③数据权限粒度 = **文档级**（切片继承所属文档）。

## 0. 范围与边界

**本期做**：

| # | 特性 | 一句话定义 |
|---|---|---|
| F1 | 删库物理索引清理 | 删除知识库时调度 `CLEANUP` 任务，drop 该库全部物理索引/collection 与别名，清偿 TODO(M4)；顺带收走快照裁剪留下的 `PENDING_CLEANUP` 行 |
| F2 | SUPER_ADMIN 收敛告警 | 启动扫描持超管角色的账号，超过一个即 warn 日志逐一点名 —— M15 升级脚本全量提权的后半句 |
| F3 | 完整多租户 | `t_kb_tenant` + 根聚合表加 `tenant_id` + MyBatis-Plus `TenantLineInnerInterceptor` 行级隔离 + 索引命名注入租户段；默认租户零迁移兼容 |
| F4 | 文档级数据权限 | 文档可见性两态（继承库 / 仅指定角色），检索与文档读取按角色裁剪，切片继承所属文档 |
| F5 | LDAP 组同步 | bind 成功后读 `memberOf`，按组→角色映射反授角色；同步授的角色与手工授的角色用 `granted_by` 区分，互不覆盖 |
| F6 | SSO 三协议 | OIDC（授权码）+ SAML 2.0（SP-initiated POST）+ CAS 3.0（ticket 校验），统一 JIT 建号与回跳协议 |
| F7 | 开放 API 终端用户反馈 | API Key 认证的反馈端点，落既有 `t_kb_retrieval_feedback` 加渠道维度，洞察页可筛 |
| F8 | 操作审计"谁"维度 | `@AuditedOperation` 注解 + 切面异步落 `t_kb_operation_audit`，覆盖全部管理台写操作，审计页可查 |
| F9 | 文档欠账 | 两份需求文档同步（工作区升 v1.18 收口 M14/M15/M16；deploy 事实源从 v1.14 回补至 v1.18）+ OpenAPI 0.16.0-m16 + CHANGELOG |
| F10 | 仓库卫生 | 清理 deploy 仓的 `.env.bak-*`、`backup/`、`backups/`、残留 `.claude/worktrees/`，补 `.gitignore` |

**本期不做**：切片级权限（数据权限止于文档，切片永远继承文档 —— 一个切片的敏感度不可能高于它所在的文档，独立授权只会制造孤儿授权）；SCIM/定时全量目录同步（组同步只在登录时刻发生，见 §6.3）；租户级配额与计费；跨租户共享知识库（隔离就是隔离，共享语义留给需要它的那一天）；IdP-initiated SAML（只做 SP-initiated，IdP 发起的断言没有对应的本地会话预期，重放面更大）。

**兼容红线**：升级即用 —— V17 建默认租户并把存量行全部划入；默认租户的索引名**不注入租户段**，物理索引零迁移；`TenantLineInnerInterceptor` 对全局表设忽略清单；三协议 SSO 全部默认关闭；文档默认可见性 = 继承库；所有新端点纯新增。

## 1. 数据模型（Flyway V17__tenant_doc_acl_audit.sql，三张新表 + 五处表升级）

### 1.1 设计取舍（写在迁移脚本文件头）

1. **tenant_id 只加在根聚合表**（user / role / kb / apikey / eval dataset / app 等直辖资源），从属资源（document / chunk / task / feedback …）经根资源归属租户 —— 给四十张表全加列不叫隔离叫散弹枪，子表查询永远先过父表的租户行过滤，再多一列只是第二个可以不一致的事实源。
2. **username 保持全局唯一**：登录页不问租户。会话令牌、登录审计、M11 以来的一切都以 username 为键，改成租户内唯一会让"当前用户"需要一个复合键，波及每一张关联表。
3. **权限目录（`t_kb_permission`）与内置角色种子是全局资源**：每个租户各自建角色（`t_kb_role` 带 tenant_id），但 18+1 个权限码的目录全租户共用 —— 码是代码里 `@RequiresPermission` 的字面量，按租户分裂目录没有任何一行代码能消费这种差异。

### 1.2 表定义

| 表 | 定义 |
|---|---|
| t_kb_tenant（新） | `tenant_id` VARCHAR(64) UK（`tnt_` 前缀）、`code` VARCHAR(64) UK、`name` VARCHAR(64)、`status` VARCHAR(16) DEFAULT 'ENABLED'、`builtin` TINYINT DEFAULT 0 + 五件套。种子：`('tnt_default0000000','DEFAULT','默认租户',builtin=1)` —— id 是字面量，理由同内置角色 |
| t_kb_doc_acl（新） | `document_id` VARCHAR(64) + `role_id` VARCHAR(64)，KEY `idx_doc(document_id)`、`idx_role(role_id)`。故意不设唯一键：改绑先删后插，与 M15 三张关联表同一纪律 |
| t_kb_operation_audit（新） | `audit_id` VARCHAR(64) UK、`tenant_id`、`user_id`、`username` VARCHAR(64)、`module` VARCHAR(32)、`action` VARCHAR(64)、`target_type` VARCHAR(32)、`target_id` VARCHAR(64) NULL、`detail` JSON NULL、`client_ip` VARCHAR(64) NULL、`request_id` VARCHAR(64) NULL + 五件套；KEY `idx_tenant_created(tenant_id, created_at)`、`idx_username(username)`、`idx_target(target_type, target_id)`、`idx_created_at(created_at)`（保留期清理扫这列） |
| 根聚合表升级 | `t_kb_admin_user`、`t_kb_role`、`t_kb_knowledge_base`、`t_kb_api_key`、`t_kb_eval_dataset`、`t_kb_app` 各加 `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' + KEY `idx_tenant(tenant_id)`；存量行由 DEFAULT 直接落到默认租户。`t_kb_role` 同时把 `uk_code(code)` 收缩为 `uk_tenant_code(tenant_id, code)` —— 建租户要复制五个内置角色，SUPER_ADMIN 这样的 code 必然每租户各出现一次，全局唯一键会把复制第一步就顶回去 |
| t_kb_user_role 升级 | 加 `granted_by` VARCHAR(16) NOT NULL DEFAULT 'MANUAL'（MANUAL / LDAP_SYNC）—— 组同步每次登录全量替换 `LDAP_SYNC` 行，`MANUAL` 行永不触碰（见 §6.3） |
| t_kb_admin_user.source 扩展 | 语义扩为 LOCAL / LDAP / OIDC / SAML / CAS（列宽 16 已够，无 DDL，仅注释更新） |
| t_kb_document 升级 | 加 `visibility` VARCHAR(16) NOT NULL DEFAULT 'INHERIT'（INHERIT 继承库可见性 / RESTRICTED 仅授权角色） |
| t_kb_retrieval_feedback 升级 | 加 `channel` VARCHAR(16) NOT NULL DEFAULT 'CONSOLE'（CONSOLE / OPEN_API）、`end_user_id` VARCHAR(64) NULL（调用方自报的终端用户标识，不做真实性背书）、`comment` 复用既有 `note` 列 |
| t_kb_search_insight 升级 | 加 `app_id` VARCHAR(64) NULL（开放接口调用方应用，控制台调试检索为空）+ KEY `idx_request(request_id)` —— 反馈端点靠它校验"这次检索是不是你的"，见 §3.6 |
| 权限码新增 | `tenant:manage`（SYSTEM 模块，第 19 码），种子只授**默认租户的** SUPER_ADMIN —— KB_ADMIN 管库不管租户；建租户复制内置角色时该码被剔除，见 §1.5 |

### 1.3 行级隔离的执行点（TenantLineInnerInterceptor）

- MyBatis-Plus `TenantLineInnerInterceptor` 注册在分页插件**之前**（官方要求，租户条件必须进 count 语句）；`TenantLineHandler.getTenantId()` 取 `UserContextHolder` 的租户，无控制台主体（开放 API、后台任务线程）时**整条跳过拼接** —— 后台任务按业务 id 精确定位行，API Key 已被应用版本限定范围，再拼租户条件需要的上下文根本不在线程里。跳过发生在 `ignoreTable()`（无主体一律返回 true），不是靠 `getTenantId()` 返回 null：后者永远返回一个合法值（无主体时回落默认租户），只为兜住"某条路径绕过 ignoreTable 还来问租户"的情况。
- **忽略清单**（`ignoreTable`）：除 §1.2 六张根聚合表外全部忽略。从属表靠父表裁剪；`t_kb_tenant`、`t_kb_permission`、`t_kb_auth_token`、两张登录/操作审计表、META 表、flyway 表天然全局。
  > 后续里程碑新增的根聚合表要一并入列，这是本节的持续义务而不是一次性清单：M19 的 `t_kb_memory_library` 漏了这一步，多租户下记忆库全员可见可改，由 Flyway V21 补齐（见 M19 契约 §1.4）；M18 的 `t_kb_web_credential` 同样漏了，由 Flyway V22 补齐（见下条）。判据是"该表是不是某个域的根、有没有从属表经它归属租户"——是，就加列 + 入围栏 + 让该域每个入口先解析根。
- **入围栏≠隔离完成：被后台线程读的表必须自己写租户谓词**（V22 的教训，本节第一段那条"无主体整条跳过"的直接推论）。围栏只在有控制台主体的线程上拼条件，所以一张表如果同时被控制台和 `@Scheduled` / 开放 API 线程读，进名单只解决了控制台那一半，另一半是**零防护**。`t_kb_web_credential` 正是这种表：控制台增删改查靠围栏，夜里的网页同步（`WebSourceService#syncEnabledSources`）在无主体线程上按 host 查凭据，围栏整条跳过。因此 `WebCredentialService#resolveFor(tenantId, host)` 把租户做成**必填入参**（租户由 `WebSource.kb_id` 反查 `t_kb_knowledge_base.tenant_id` 得到，拿不到就返回"无凭据"、绝不退化成按 host 查）。
  > 这一条比 V21 那条更要紧，因为它的失败形态是静默的：只给表加 `tenant_id` 列、只把表名加进围栏，控制台看起来隔离好了，抓取仍在跨租户取用凭据，而且比修复前更难发现——修复前是"共享一份全局凭据"这个明面上的错，加列之后变成"看起来已隔离、实际仍串号"。**判据**：新表进围栏时先问"除了控制台，还有谁读这张表"，有后台读者就必须同时给出一条显式带租户的查询入口。

### 1.3.1 围栏名单现状（代码事实源：`KbTenantLineHandler.FENCED_TABLES`）

| 表 | 入列版本 | 是否有后台读者 | 后台侧隔离手段 |
|---|---|---|---|
| `t_kb_admin_user` / `t_kb_role` | V17 | 否 | —（运营商例外见下条） |
| `t_kb_knowledge_base` / `t_kb_api_key` / `t_kb_eval_dataset` / `t_kb_app` | V17 | 否 | — |
| `t_kb_memory_library` | V21 | 开放端（`kb-mk-*`） | Key 绑库 + `user_id` 查询谓词，刻意不拼租户 |
| `t_kb_web_credential` | V22 | 网页同步 `@Scheduled` | `resolveFor(tenantId, host)` 显式租户谓词 |

### 1.3.2 从属表的解析义务（围栏名单之外的另一半）

§1.3.1 那张表回答的是"哪些表自己带 `tenant_id`"，它回答不了"不带列的那几十张表靠什么隔离"。答案是 §1.1 取舍①：从属表经根表归属租户。但这句话只在**每个入口都先解析到根表**时才成立 —— 只要有一条入口按从属表自己的业务 id 直接寻址、不查根表，那条路径上的租户隔离就是零，而不是弱。围栏在那条语句上什么都没做，也没有任何东西会报错。

| 从属表 | 归属路径 | 解析入口 | 补齐版本 |
|---|---|---|---|
| 记忆库五张从属表（片段/画像规则、节点、画像、Key） | `library_id` → `t_kb_memory_library` | `MemoryLibraryGuard`（管理端 21 个入口） | V21 |
| `t_kb_web_source` | `kb_id` → `t_kb_knowledge_base` | `WebSourceGuard`（网页导入 4 个入口） | V22 后修复 |
| `t_kb_app_version` | `app_id` → `t_kb_app` | `AppVersionGuard`（`AppVersionService#require` 背后，覆盖应用版本 5 个入口） | M4c 后修复 |
| `t_kb_document` / `t_kb_chunk` / `t_kb_annotation` / `t_kb_ext_source` / `t_kb_retrieval_feedback` | `kb_id` → `t_kb_knowledge_base` | `KbResourceGuard`（按自身 id 寻址的 43 个端点） | M16 后修复 |
| `t_kb_eval_case` / `t_kb_eval_run` | `dataset_id` → `t_kb_eval_dataset`（该表自身在围栏内） | `KbResourceGuard` + `EvalRunService#requireRun` / `EvalDatasetService#requireCase` | M16 后修复 |
| `t_kb_api_audit_log` | `key_id` → `t_kb_api_key` | `ApiAuditService#visibleKeyIds`（列表与统计，见下） | M16 后修复 |

- **`t_kb_app_version` 是同一个模子里的第四处**：五个端点（`GET /app-versions/{vid}`、`PUT .../gate-dataset`、`POST .../submit-test`、`POST .../release`、`POST .../rollback`）只有 `@RequiresPermission` 功能权限码，`AppVersionService#require` 是从属表上的裸 `selectOne`、从不查 `t_kb_app`。任何租户持 `app:release` 就能发布 / 回滚别家的应用版本 —— 这一条直接改变别人对外 API 被服务的内容；持 `app:read` 就能读它的配置快照（关联知识库与模型配置）。**发布是其中最贵的入口**：它还会在门禁执行器上对别家知识库启动同语料双跑，花掉他们的检索与模型调用额度，并冻结索引快照。
- **守卫可以落在服务方法背后，而不是每个入口前面**：`AppVersionGuard` 被 `AppVersionService#require` 独占调用，因为该方法是 11 处调用方的唯一入口（本服务自调用 5 处、`ReleaseGateService` 5 处、控制台预览 1 处）。这与"检查放服务层不放 Controller"是同一条原则的更强形态 —— 收口点越靠近数据，新入口自动继承的概率越高。做成独立 bean 的理由不变：可 grep、可单测、可复用。
- **跨租户与不存在必须是同一个回答**：`AppVersionGuard` 两处失败都抛 `VERSION_NOT_FOUND` + 同一句文案，文案不含 `appId`。第二跳报成 `APP_NOT_FOUND` 会用错误码差异告诉调用方"你猜的 id 是真的、只是在别人那里" —— 这是 404 收口在措辞上的延伸，光返回 404 不够。
- **`t_kb_web_source` 不进围栏名单是对的，漏的是解析**：它是 M12 建的从属表，经 `kb_id` 归属租户，给它加 `tenant_id` 就是造第二个可以不一致的事实源。真正的缺陷是四个入口（`POST /web-sources/{sourceId}/sync`、`PUT /web-sources/{sourceId}`、`DELETE /web-sources/{sourceId}`、`GET /kb/{kbId}/web-sources`）压根不查 `t_kb_knowledge_base` —— 任何租户凭一个 `sourceId` 就能触发别家网页源的抓取、改它的同步开关、硬删它的登记（`hardDeleteById`，不可恢复），凭一个 `kbId` 就能列出别家知识库登记的全部 URL 与同步状态。
- **数据范围守卫不是租户守卫，这是本次缺陷的根因**：`KbScopeGuard` / `AccessGuard.requireKbAccess` 回答的是"这个库在不在调用者角色配的数据范围里"，从头到尾没有一处比对租户；而且每个方法第一行的 `unrestrictedKbScope()` 短路对 `kb_scope_all` 的账号（租户的 SUPER_ADMIN、未配数据范围的 KB_ADMIN，都是常见配置）直接放行。已被删除的 `KbScopeGuard#requireWebSourceAccess` 就站在这四个入口前面，看起来像守卫、实际一行租户判断都没有 —— **一个只覆盖数据范围的守卫比没有守卫更危险，它让 review 以为这条路径已经守住了**。
- **同族的另外 8 个方法在 M16 后修复中一并补齐**（`KbScopeGuard` 已重命名为 `KbResourceGuard`）：`requireWebSourceAccess` 只是这一族里第一个被发现的成员，document / chunk / annotation / dataset / case / run / ext-source / feedback 八个方法逐字同构，站在 43 个控制台端点前面。9 个方法一律改成"先解析围栏根表（404）、再判数据范围（403）"，短路整体删除。**类名一起换掉是修复的一部分**：`ScopeGuard` 诚实地描述了它当时做的事，而那件事不是隔离边界，留着这个名字下一个 review 还会误读。
  > **短路吞掉的不只是数据范围判定，这条比缺陷本身更值得记住**：`requireDatasetAccess` 查的 `t_kb_eval_dataset` 本来就在围栏名单里、围栏会自动给它拼租户条件——但方法第一行的 `return` 让那条语句压根不执行。**围栏只保护它实际发出的语句**；任何"提前 return"都会连同已经写好的围栏一起跳过，而这种失败在代码上看不出来，因为围栏是拦截器、不在方法体里。
- **「入口自带 `kb_id`」不等于已解析**：这类入口最容易被误判为安全，因为路径里那个 `kbId` 看着就是作用域本身。但它是**调用方声明的**作用域，不是被证实的——不查根表就没有任何东西证实过它属于调用者的租户，而按 `kb_id` 过滤的那条从属表语句会照常执行。M16 后修复补齐了 15 个这样的入口（文档列表 / 回收站 / 检索洞察与统计 / 批量删除与重建 / 批量确认 / 全库重建与状态 / 文档密级读写），落点一律在服务层方法首行。**判据**：这条链路上有没有一次对 `t_kb_knowledge_base` 的查询？没有 → 未守，`kbId` 在路径里也一样。
- **解析义务的形态**：入口自带 `kb_id`（列表、登记）→ 直接解析根表，从属表一条语句都不发；入口只有从属表自己的 id（按 `source_id` 同步/改/删）→ **先定位、再解析根**，定位那条 `select` 物理上无法避免（`source_id` 只存在于从属表），但它只读、不改任何状态，判定发生在紧接着的根表那一跳，跨租户在那里读作"不存在"，后续的写语句与抓取一条都不发出。两种形态都以 **404** 收场（与 §1.3 记忆库同口径），不是 403。
- **租户判定必须排在数据范围判定之前**：先问数据范围会让跨租户的资源答 403、不存在的资源答 404，这个差别本身就告诉调用方"这个 id 在别的租户里存在"。`WebSourceGuard` 的顺序是租户（404）→ 数据范围（403），单测钉住。`PUT /app-versions/{vid}/gate-dataset` 是这条规则的第二个落点：它携带第二个资源（`datasetId`），原先在 Controller 里先判该资源的数据范围，跨租户的版本会先撞上评测集的 403；`kbScopeGuard.requireDatasetAccess` 已移入 `AppVersionService#setGateDataset` 并排在版本解析之后。**入口带第二个资源时，主体资源的租户判定仍然排第一** —— 顺序看的是资源的角色，不是参数在签名里的位置。
- **过滤型入口的第三种形态：没有任何 id 可解析**。前两种形态（路径带从属资源 id、路径带 `kbId`）都有一个东西可以拿去解析根表；`GET /api-audit-logs` 的 `key_id` 是**可选**过滤参数，缺省时无从解析——而"没有 id"在这里恰恰等于"全部署所有租户的调用流水"。这类入口要反过来做：先经围栏读出调用者租户下的根表 id 集合（`t_kb_api_key`），再用它约束从属表的 `in`。**空集合必须答空，绝不能退化成"不加过滤"**——那会把缺陷原样保留，而且比修复前更难发现，因为代码看起来已经解析过了。判据：可选过滤参数缺省时，这条语句的作用域是什么？答案是"全表"就是未守。
- **判据（新增入口时自查）**：这个入口的路径参数是根表的 id 吗？不是 → 它必须过本域的守卫。守卫做成独立 bean、检查放服务层不放 Controller —— Controller 里的守卫只护得住有人记得加的那几条路径，而服务方法是所有调用方的必经之路。

- 平台超管跨租户：**默认租户的 SUPER_ADMIN** 是唯一能看到租户管理页的人；其余一切读写都被钉死在自己租户内，包括默认租户超管的日常操作 —— 跨租户视角只存在于 `TenantController`，不存在"切换租户"的全局态，全局态是每一个越权 bug 的温床。
- **唯一的栅栏例外**：持 `tenant:manage` 者对 `t_kb_admin_user`、`t_kb_role` 两表不拼租户条件 —— 否则运营商无法为新租户建首个账号、授其角色、移户，建出来的租户永远没人能登录。其余四张根表即使运营商也钉死本租户：库、Key、数据集、应用的日常操作不需要跨租户视角。配套约束：角色授予校验角色与用户同租户（绑定表自身无 tenant 列，栅栏拦不住这类泄漏）；username 全局唯一校验走 `@InterceptorIgnore(tenantLine)` 的跨租户查询；移户（`PUT /users/{userId}/tenant`，`tenant:manage`）清空旧角色绑定并吊销会话。

### 1.4 索引命名注入租户段（IndexNaming）

- 物理名与别名规则：默认租户**维持现状**（`kb_{kbId}_...`），非默认租户注入段 —— `kb_{tenantSegment}_{kbId}_{embedding}_{snapshot}`、别名 `kb_{tenantSegment}_{kbId}_{engine}`；`tenantSegment` = tenant_id 去 `tnt_` 前缀小写化，处理方式与 `normalizeKbId` 同构。
- **默认租户不注入是兼容红线而非偷懒**：注入会让存量部署升级后所有别名失联，检索全空。新租户从第一个索引起就带段，物理层面与默认租户互不可见。
- `IndexNaming` 全部公开方法加 tenant 参数（调用点均在 kb-app，编译期收口）；kbId 本身在库表内全局唯一（`kb_` + UUID16），租户段的价值是**运维可辨认**与"跨租户挂错别名在名字上就能看出来"，不是防碰撞。

### 1.5 平台级权限码不下发到子租户

`tenant:manage` 有两重能力：建/停租户（`TenantController` 类级守卫），以及让 `t_kb_admin_user`、`t_kb_role` 两表不拼租户条件（§1.3 的栅栏例外）。它落到任何子租户的角色上，该租户的管理员就同时拿到了这两样 —— 能建租户、停别人的租户、看全平台的用户和角色，多租户隔离从根上失效。

权限码目录是全租户共用的（§1.1 取舍③），子租户管理员在角色编辑页确实能看到这个码，所以这条不是配置建议而是不变量：**非默认租户的角色不得持有平台级权限码**（`PermissionCodes.PLATFORM_ONLY`，当前仅 `tenant:manage`）。守在授予的唯一入口 `RoleService.replacePermissions(Role, codes)` 上，人工勾选命中即 403。

建租户复制内置角色（§3.1）走的是同一个入口，但语义不同：复制时**剔除**平台级码而非报错 —— 默认租户的 SUPER_ADMIN 本来就持有它，逐行照抄会把它搬进每个新租户，而报错则会让建租户整个失败。剔除后新租户的 SUPER_ADMIN 在自己租户内仍是全权。

## 2. 配置键（KbProperties.Auth 扩展 + Audit 新增）

| 键 | 环境变量 | 默认 | 语义 |
|---|---|---|---|
| kb.auth.ldap.group-sync.enabled | AUTH_LDAP_GROUP_SYNC_ENABLED | false | 开启后每次 SSO 登录读组反授角色 |
| kb.auth.ldap.group-sync.role-mappings | AUTH_LDAP_GROUP_ROLE_MAPPINGS | 空 | `groupDn=ROLE_CODE` 分号分隔；DN 比较忽略大小写与空白 |
| kb.auth.oidc.enabled / issuer / client-id / client-secret / scopes | AUTH_OIDC_* | false / 空 / 空 / 空 / `openid profile email` | issuer 用于发现文档 `{issuer}/.well-known/openid-configuration` |
| kb.auth.saml.enabled / idp-entity-id / idp-sso-url / idp-certificate / sp-entity-id | AUTH_SAML_* | false / 空 / 空 / 空(PEM) / `kb-rag` | 证书是验签唯一信任锚，PEM 直接进环境变量 —— 引一个 metadata 拉取器就要处理它的可用性 |
| kb.auth.cas.enabled / server-url | AUTH_CAS_* | false / 空 | server-url 如 `https://cas.corp.example.com/cas`，校验走 `/p3/serviceValidate` |
| kb.auth.sso.web-base-url | AUTH_SSO_WEB_BASE_URL | 空 | 回调成功后重定向的前端地址，如 `https://kb.corp.example.com`；空则三协议即使 enabled 也判未配置 |
| kb.audit.operation-retention-days | AUDIT_OPERATION_RETENTION_DAYS | 180 | 操作审计保留期，对齐 M6 API 审计的 180 天 |

- 三协议彼此独立开关，可同时开 —— `GET /auth/sso/providers` 返回已就绪列表（enabled 且必填项非空），登录页有几个渲染几个按钮。LDAP 页签沿用 M15 `sso-available`，两者并存：LDAP 是"输本地页面的域凭据"，三协议是"跳出去认证"。

## 3. REST 契约

### 3.1 租户（TenantController，`/api/v1/tenants`，类级 `tenant:manage`，仅默认租户超管可达）

`GET`、`GET /{tenantId}`、`POST`（`{code, name}`）、`PUT /{tenantId}`（仅 name）、`PUT /{tenantId}/status`。**无 DELETE**：租户下挂着索引、文件与审计，删除语义 = 先停用后人工清算，给一个级联删几十张表外加 drop 索引的按钮是事故预定。停用租户 → 该租户全部账号登录被拒 + 存量会话由 `PrincipalResolver.resolve` 兜截（下一次请求 401）。`code`/`builtin` 不可改，DEFAULT 租户不可停用（把所有人锁在外面没有恢复路径，同 M15 requireNotSelf 论证）。

- 建租户时自动为其复制五个内置角色（新 role_id，`builtin=1`）——否则新租户第一个账号无角色可授；首账号由默认租户超管在用户管理页建（用户管理页对超管增加租户选择）。

### 3.2 文档可见性（挂在 DocumentController，`doc:review` + 库范围）

| 端点 | 说明 |
|---|---|
| GET /api/v1/kb/{kbId}/documents/{docId}/visibility | → `{visibility, role_ids}` |
| PUT /api/v1/kb/{kbId}/documents/{docId}/visibility | `{visibility, role_ids}`；`INHERIT` 时 role_ids 必须为空数组（理由同 M15 kb_scope_all）；`RESTRICTED` 时非空 |

- 权限码用 `doc:review` 不用 `doc:write`：改可见性是治理动作不是编辑动作，EDITOR 能改自己文档的密级等于没有密级。
- 变更即生效于检索（无缓存层，见 §5.2）；文档列表页所有人可见全部行但带"受限"标记 —— 列表是管理视图，藏行会让管理员数不清自己的库；**内容读取**（详情 / 切片 / 原文下载 / 预览）对无授权者 403。

### 3.3 SSO（AuthController 扩展，`/api/v1/auth`，全部免登录）

| 端点 | 说明 |
|---|---|
| GET /sso/providers | 免认证 → `{providers: [{type: OIDC/SAML/CAS, display_name}]}`，登录页据此渲染 |
| GET /oidc/login | 302 到 IdP 授权端点，携带 state（随机 32 字节，进 `t_kb_auth_token` 同款存储、10 分钟过期、一次性） |
| GET /oidc/callback | 验 state → code 换 token → 验 id_token（JWKS 验签 + iss/aud/exp）→ 取 `preferred_username`/`email` → §3.5 统一落地 |
| GET /saml/login | 302 到 IdP SSO URL，携带 deflate+base64 的 AuthnRequest 与 RelayState（同 state 纪律） |
| POST /saml/acs | 验 Response 签名（JDK XMLDSig + 配置的 IdP 证书）→ 验 NotOnOrAfter/Audience/InResponseTo → 取 NameID → 统一落地 |
| GET /cas/login | 302 到 `{server-url}/login?service={callback}` |
| GET /cas/callback | 持 ticket GET `{server-url}/p3/serviceValidate` → 解析 XML 的 `cas:user` → 统一落地 |

### 3.4 三协议实现纪律

- **零重框架**：OIDC 引 `nimbus-jose-jwt`（只为 JWKS 验签，纯库无传递依赖）；SAML 用 JDK 自带 `javax.xml.crypto.dsig` 验签 + DOM 解析，**XML 解析器必须关外部实体**（XXE 是 SAML 的第一号历史漏洞）；CAS 纯 HTTP + DOM，零新依赖。
- 每协议一个 kb-domain port 风格的独立组件（`OidcClient` / `SamlProcessor` / `CasValidator`，kb-infrastructure 实现），失败三态对齐 M15 `DirectoryBindResult`：断言无效 = INVALID_CREDENTIALS，IdP 不可达 = SERVICE_UNAVAILABLE（不计锁定，沿用 §M15-5.2）。
- 回调成功后 302 到 `{web-base-url}/login#sso_token={token}`：token 放 **fragment 不放 query** —— fragment 不进服务器日志、不进 Referer。前端 LoginPage 挂载时检出 hash、存 token、清 hash、进控制台。

### 3.5 SSO 统一落地（三协议与 LDAP 共用）

外部身份 → 归一化登录名（小写、去域后缀，沿用 M15）→ 已存在则校验 source 匹配与状态（LOCAL 账号被 SSO 命中 → 拒，`WRONG_LOGIN_MODE`，同 M15 §5.3）→ 不存在则 JIT 建号（`provisionDirectoryUser` 泛化为 `provisionExternalUser(username, source, displayName, email)`，授 `ldap.default-role-code` 同款默认角色）→ 组同步（仅 LDAP，见 §6.3）→ 发本地 token。**JIT 建号落在默认租户**：外部 IdP 没有租户概念，映射规则是运维决策，本期不做 claim→tenant 映射，建号后由超管移户（`PUT /users/{userId}/tenant`，`user:manage` + 仅默认租户超管）。

### 3.6 开放 API 终端用户反馈（KnowledgeOpenApiController，`/api/v1/knowledge`，API Key 认证）

`POST /feedback`：`{request_id, chunk_id, verdict(GOOD/BAD), comment?, end_user_id?}` → `{feedback_id}`。落 `t_kb_retrieval_feedback`，`channel=OPEN_API`，kb_id/query 由 request_id 反查检索留痕补齐（查不到 → INVALID_PARAM"request_id 无效或已过期"—— 匿名反馈无法转评测用例，是垃圾数据不是数据）。限流沿用该 Controller 既有 API Key 限流；`comment` 截断 512 落 `note` 列。控制台反馈列表与洞察页加 channel 筛选。

**request_id 不是凭据，授权靠 app 归属**：`request_id` 走 `X-Request-Id` 头进来、调用方可自选，光凭"这个 id 在洞察表里存在"就放行，等于任何持合法 Key 的应用只要拿到别人的 request_id，就能对自己无权访问的知识库写反馈（伪造 verdict、512 字评论、end_user_id）。因此 `t_kb_search_insight` 增 `app_id` 列（开放接口写入调用方应用，控制台调试检索留空，并补 `idx_request` —— 反查本来就在全表扫），反馈端点把 `ApiKeyPrincipal` 传进应用层，用 Key 已有的那道 `requireAccessTo(insight.app_id)` 校验归属：
- 洞察行 `app_id` 为空（控制台调试检索）→ 与"id 不存在"同一句拒绝，本渠道不覆盖控制台检索；
- 洞察行的应用不在该 Key 的授权范围内 → `APP_ACCESS_DENIED`；
- `chunk_id` 存在但不属于该次检索的 kb → INVALID_PARAM"chunk_id 不属于该次检索的知识库"（那次检索只可能返回本库分片，跨库 id 没有正当来源；分片**已删除**仍按控制台同款宽容处理，只是不落 `doc_id` —— 删除不是越权信号）。

### 3.7 操作审计（OperationAuditController，`/api/v1/operation-audits`，类级 `audit:read`）

`GET ?module=&username=&target_id=&from=&to=&page=&size=`、`GET /{auditId}`。查询钉死在调用者自己的租户（根聚合表之外，本表在拦截器忽略清单里，租户条件由 service 显式拼 —— 审计表要为"跨租户排障"留全局行，隐式裁剪会让这个例外不可见）。

## 4. F1/F2：遗留清偿

### 4.1 删库物理索引清理（CLEANUP 任务落地）

- `KnowledgeBaseService.delete()` 中 TODO(M4) 位置改为：该库全部 `t_kb_index_registry` 行置 `PENDING_CLEANUP` + 插入一条 `TaskType.CLEANUP` 任务（t_kb_task，payload 只带 kbId）。
- 新执行器 `IndexCleanupService`：扫该库 `PENDING_CLEANUP` 行逐一 `dropIndex(physicalName)`（`indexExists` 先探，不存在视为已清 —— 幂等是补偿重试的前提），再删两个别名与 `mm` 索引，成功后 registry 行逻辑删除。**顺带收走 M7 快照裁剪置下的存量 `PENDING_CLEANUP` 行**：同一状态同一执行器，快照裁剪从"只改状态"变成真正释放磁盘。
- 失败走既有 sync compensation 扫表重试（CLEANUP 任务行留在 t_kb_task，重试上限同款）；引擎不可达不阻塞删库主流程 —— 删库的用户等的是"库没了"，不是"磁盘回来了"。

### 4.2 SUPER_ADMIN 收敛告警

`ApplicationRunner`（对齐既有 bootstrap 账号初始化的位置）：统计 `granted_by` 不限、角色 code=SUPER_ADMIN 的启用账号；数量 > 1 → error 日志逐一列出 username 并附一句"M15 升级曾全量提权，请按最小权限重排"。**只告警不动权**：自动降权一定会在某个只剩一个"正确超管"的部署里降错人。每次启动都报，直到收敛 —— 一次性标记会被重启吞掉。

**逐租户各报一条**：V17 把角色 code 的唯一范围从全局收到租户内（§1.2），`SUPER_ADMIN` 此后在每个租户各有一行，按 code 取一行（`limit 1`）只能看到任意一个租户，其余租户的超权账号永远不会被点名 —— 恰好是这个巡检要堵的盲区。日志带 `tenantId`，运维才知道该开哪个租户的用户管理页。总数不做汇总：最小权限是租户内部的问题，三个租户各有一个管理员是稳态，加起来报"3 个"会读成平台级异常。

### 4.3 图谱抽取吞吐改造（任务处理效率）

现象：一万分片规模的库开启 GraphRAG 后，"重新抽取"进度条以小时计。三层原因，逐层拆：

**① 批次栅栏空转（主因）**。原实现按 `extract-batch-size=10` 分批提交，每批 `allOf().join()` 等齐再提交下一批。LLM 单次延迟方差极大（中位数几秒、P99 可到十几秒），每批都被最慢的那一个卡住，池子在批尾空转，实际吞吐远低于并发数所暗示的值。改为**流水线**：全部分片一次性提交给固定大小线程池（池大小本身就是并发闸门），谁完成谁接下一个，没有栅栏。`extract-batch-size` 随之删除 —— 它描述的机制已经不存在，留着就是一个改了没反应的旋钮（存量 `.env` 里的 `GRAPH_EXTRACT_BATCH_SIZE` 被忽略，无需清理）。

**② 并发默认值过低**。`extract-concurrency` 从 2 提到 8。原值之所以只能是 2，是因为图 schema 用复合索引而非唯一约束（`Neo4jGraphStore` 类注释的取舍：唯一约束的社区版/企业版行为不一致，一条建不出来的 schema 语句会让整个能力不可用），并发 MERGE 同名实体会打架，于是"抽取并发很小"成了正确性的前提 —— 提并发就是拿正确性换速度。所以先解开这个绑定：

**③ 两阶段拆分（②的前提）**。一次抽取 = 模型调用（秒级、纯 socket 等待、无共享状态）+ 图写入（毫秒级、要 MERGE、有竞态）。两者性质相反，合在一个 worker 里就只能按更严的那一半限流。拆成：N 路并发抽取 → **单写入者线程**串行落图。于是模型调用可以放宽到只受限流约束，而同一个库的 MERGE 依然一次一个 —— 比原来并发 2（两个线程同时 MERGE）**更安全**。跨库不冲突（`(kb_id, name)` 的 kb_id 不同），所以串行化是每次运行一个写入者，不是全局一个。不做攒批写入：一批事务失败会让整批分片一起丢，违背"一个坏答案只损失一个 passage"，而图写入本来就不是瓶颈。

**④ 进度上报**。流水线下每完成一个分片都会回调，一万分片就是一万次 `t_kb_task` 更新。改为按整数百分点节流（最多 100 次），由 `getAndAccumulate` 无锁决定"谁推进了百分比谁写库"。同时从 `updateById(整行)` 改为按列 update：`KbTask` 带 `@Version` 乐观锁，多线程整行写会互相顶掉，而任务进度更新没有地方上报冲突，失败是静默的。

**⑤ 抽取任务独立线程池**（`GRAPH_EXECUTOR`，core 1 / max 2 / queue 50）。原来 `runFullExtraction` 与 `onVersionActivated` 挂在 `INDEX_EXECUTOR`（core 2 / max 4）上，而它们是全系统唯一以小时计的任务：两个全量抽取就能占住索引池一半到全部，文档上传排在后面等一个明天才结束的活。池子按"线程被占多久"分，不按模块分。模型侧峰值并发 = 池大小 × `extract-concurrency`（2 × 8 = 16），两个旋钮的乘积，运维据此对齐限流额度。

### 4.4 文档索引吞吐改造（任务处理效率）

与 §4.3 同一个诉求，落在文件解析这条链路上。三处，从内到外：

**① 嵌入批次串行 → 并发**。`ChunkEmbedder` 原按 provider 批大小（`EMBEDDING_BATCH_SIZE`，默认 10）切批，但**批次之间串行**：500 个分片就是 50 次网络往返，每次半秒到两秒，光嵌入这一段就要半分钟到两分钟。批次之间没有任何依赖，纯粹是等 socket。改为并发跑，新增 `EMBEDDING_CONCURRENCY`（默认 4）。

并发上限做成**共享线程池而非每次调用新建**：这个值的意义是"嵌入服务同时收到几个请求"，几个文档同时索引时若各自开池，总并发就失控了。队列满时用 `CallerRunsPolicy` —— 提交者（索引线程）自己跑一批，形成背压，不丢批次（丢一批就是丢一批向量）。单批次直接跑不进池：注解修改路径一次只嵌一个分片，调度它比直接跑更贵。

失败语义不变：一批失败即整次调用失败，上层把版本标 FAILED —— 半个版本嵌完不能当成索引成功。但**必须把 `CompletionException` 解包还原原始异常**：`IndexPipelineService` 按 `BizException` 的错误码区分 `PARSE_FAILED` 与 `INDEX_FAILED`，包一层会丢掉这个分支，还会把 `java.util.concurrent.CompletionException:` 写进运维可见的 `fail_reason`。

**② 嵌入状态逐行落库 → 按批一条语句**。原实现每个分片一次 `updateById`，500 个分片就是 500 条 UPDATE。同一批次里状态是同一个值（DONE），一条 `UPDATE ... WHERE chunk_id IN (...)` 就够，500 条降到 50 条。同时从"整行写"改为"按列写"：`Chunk` 继承 `BaseEntity` 带 `@Version`，并发批次整行写会互相顶掉乐观锁版本号，而这里没有地方上报冲突。内存里的对象仍照旧 `setEmbeddingStatus`，调用方拿着同一批对象继续往下走。

**③ `INDEX_EXECUTOR` 的 `max` 是死配置**。原配置 `core=2 / max=4 / queue=200`。`ThreadPoolTaskExecutor` 只在队列**满**之后才扩到 max，而队列有 200 深 —— 实际稳态并发恒为 **2**，`max=4` 永远不可达，批量上传 50 个文件是两个两个处理的。改为 `core=max=4`，让写下来的数字就是真实并发。取 4 而非机器核数：这个线程一生都在等 parser 服务、嵌入服务与引擎，本机 CPU 上只有切分那一小段。

**两级并发相乘**：索引池决定几个文档同时在跑（4），文档内部嵌入批次再并发（全局 4）。嵌入池是全局上限，所以 4 个文档同时索引时它们**共享**这 4 路，不会把限流打穿 —— 单文档会比独占时慢，但总吞吐不降。填 `EMBEDDING_CONCURRENCY=1` 即恢复改造前的串行行为。

**没做的**：解析阶段本身（在 parser 服务侧，server 只是等 HTTP 响应）；索引写入的攒批（`ChunkIndexWriter` 已走引擎 bulk）。

### 4.5 并发参数配置化与按机器调优

§4.3/§4.4 把并发提上去之后，暴露出一个更基础的问题：**这些池的大小全是硬编码常量**，运维想按机器规格调只能改代码重编译。而"该调多大"本质上不由代码决定 —— 索引线程一生都在等外部服务，天花板是下游能吃下多少，一台 10 核跑全套中间件的主机和一台笔记本要的数字不一样。

配置化四处，默认值一律保持原样（面向最小部署，升级无行为变化）：

| 变量 | 含义 | 默认 |
|---|---|---|
| `INDEX_CONCURRENCY` | 同时索引几个文档 | 4 |
| `GRAPH_TASK_CONCURRENCY` | 几个知识库能同时重建图谱 | 2 |
| `PARSER_MAX_WORKERS` | 解析服务工作线程数（parser 侧） | 4 |
| `EMBEDDING_CONCURRENCY` | 单文档嵌入批次并发（§4.4 已加） | 4 |

`GRAPH_EXECUTOR` 也踩了 §4.4 ③ 同一个陷阱：`core=1 / max=2 / queue=50` —— 队列 50 深意味着 `max=2` 永不可达，实际恒为 1。配置化时一并改为 `core=max`。

**顺带修掉 parser 侧一处并发错配**：OCR 调用池原本是固定 2，注释理由是"每次解析里 OCR 逐页串行"——但这个池被**所有** parser worker 共享，4 个 worker 同时解析扫描件时有 2 个在排队。这个池的职责只是给单页 OCR 调用套超时，不该成为吞吐限制，现改为跟随 `PARSER_MAX_WORKERS`。

**三个不随机器变大的天花板**（调参前必须知道，否则调大只是把瓶颈换个地方）：

1. **模型侧限流** —— DashScope 嵌入/聊天/vision 各自的 QPS 与并发额度。撞限流的表现是**任务失败率上升而不是变慢**（被限流的调用记为失败且不重试），所以调大后要先看 error 日志再往上加。
2. **`PARSER_MAX_WORKERS`** —— 解析是真 CPU 密集（PDF 文本抽取、页面渲染、图片解码）且 uvicorn 单进程。把 `INDEX_CONCURRENCY` 调到远超它不会更快，只是把队列从 server 挪到 parser 门口。
3. **`MYSQL_POOL_SIZE`** —— 并发调大不扩连接池，瓶颈只是从"慢"换成 connection timeout。索引链路没有 `@Transactional`（mapper 调用短借短还），所以不会出现"持连接等子任务"的死锁，连接池只需覆盖瞬时峰值：索引并发 + 嵌入并发 + 图谱 3 + 在线检索 16 + 审计/Web 若干。

**10 CPU / 64GB 单机参考配置**（同机还跑 MySQL + ES + Qdrant + Redis）：

```
INDEX_CONCURRENCY=8          # 比 PARSER_MAX_WORKERS 略高，让解析服务始终有活干
EMBEDDING_CONCURRENCY=12     # 纯网络等待，可超核数；受嵌入限流约束
GRAPH_EXTRACT_CONCURRENCY=12 # × GRAPH_TASK_CONCURRENCY = 聊天模型峰值 24
GRAPH_TASK_CONCURRENCY=2
IMAGE_DESCRIBE_CONCURRENCY=12
EVAL_CONCURRENCY=8
PARSER_MAX_WORKERS=6         # 真 CPU 活：10 核给 6，留 4 给 ES/MySQL/server/系统
MYSQL_POOL_SIZE=48           # MySQL max_connections=151，余量充足
```

聊天模型的限流通常比嵌入更紧，所以 `GRAPH_EXTRACT_CONCURRENCY × GRAPH_TASK_CONCURRENCY` 这个乘积是最容易撞限流的一项。

### 4.6 抽取延迟的实测归因与降延迟改造

§4.3 把抽取改成流水线、并发提到 8 之后，一次 385 分片的抽取仍要 20 分钟。**并发从 2 提到 12（6 倍）几乎没变快**，这个反常现象是定位的起点：说明瓶颈不在并发那一段。

逐段排除，全部有实测数据：

| 环节 | 实测 | 结论 |
|---|---|---|
| Neo4j 写入（单写入者串行） | 同形语句压测 20 分片约 1 秒 → 每片约 50ms，385 片共约 19 秒 | 占 1.6%，**不是瓶颈** |
| 热点实体度数 | 最大 88 | MERGE 不退化 |
| Chunk MERGE 索引 | `EXPLAIN` 显示 `NodeByLabelScan` | 真缺陷但 713 节点扫描是微秒级，**当期非瓶颈** |
| LLM 调用 | 反算 `1200s × 12 ÷ 385 = 37.4 秒/次` | **就是它** |

37.4 秒是怎么来的，用图里的实测均值精算即可闭合：

```
实体 16.2/片 × (22 结构 + 5.5 名长 + 7 类型)  ≈  559 token
关系 19.9/片 × (30 结构 + 5.5×2 端点名 + 4.4)  ≈  903 token   ← 占 61%
单次输出 ≈ 1482 token，占 max_tokens=2048 的 72%
qwen-plus 约 40 token/s → 1482 ÷ 40 = 37 秒   ✓ 与反算的 37.4 秒吻合
```

两个结论直接落地：**抽取延迟 ≈ 输出 token 数 ÷ 模型生成速度**，与并发无关；而均值就占了 72% 预算，长尾必然溢出 —— 实测 35/385（9%）的分片因 JSON 被截断而**整片丢失**（解析器日志 `reason=no json object boundary`）。

四处改动，分别打这两个因子：

1. **抽取模型可独立配置**（`GRAPH_EXTRACT_MODEL`，默认空 = 沿用 `CHAT_MODEL`）。抽取与查询改写对模型的要求相反：改写是一句话要语感，抽取是照固定 JSON 形状填空要吞吐。为改写质量选的 qwen-plus 让抽取按 40 token/s 去生成上千 token，turbo 档能把生成速度翻倍以上，而"填 JSON"的质量损失远小于改写。与 `EVAL_JUDGE_MODEL` 同一模式，不需要第二份凭据。

2. **提示词加数量上限**（`GRAPH_EXTRACT_MAX_ENTITIES`，默认 24，实体与关系共用）。原提示词对数量毫无约束，模型会把长分片能想到的都抽出来。常规分片（16/20）碰不到这个上限，长尾则被截在上限而不是截在半个 JSON 上 —— **"截断丢整片"变成"限量保主要"**。同时要求紧凑 JSON（无换行无多余空格），结构开销也随之下降。上限有下限保护（< 4 夹到 4）：0 或负数会让提示词变成"什么都别抽"，抽取静默产出空图。

3. **生成预算 2048 → 3072**（`GRAPH_EXTRACT_MAX_TOKENS`）。给长尾留余量。与 ② 不冲突而是互补：② 控常规输出长度，③ 兜长尾不被截断。

4. **修 Chunk MERGE 的索引未命中**。`MERGE (c:Chunk {chunk_id})` 用不上 `kb_chunk_lookup(kb_id, chunk_id)` —— 复合索引只服务提供了前导属性的查找，所以退化成扫描全部知识库的全部 Chunk 节点，随语料增长。补上 `kb_id` 谓词后 `EXPLAIN` 从 `NodeByLabelScan` 变 `NodeIndexSeek`。chunk_id 本就全局唯一，加谓词不缩小任何原本成立的范围。

**顺带修掉一个真 bug**：`ModelProviderConfig` 里派生抽取/判题 provider 的两个复制方法都漏复制了 `generateTimeoutMs`（读超时）。字段默认值恰好也是 60000 所以一直没暴露，但部署方设了 `CHAT_GENERATE_TIMEOUT_MS` 时对这两条链路静默无效 —— 而抽取答案是全系统最长的一次生成，正是最需要这个预算的地方。

**限流（429）此前被当成"坏答案"处理**。并发调到 24（峰值 `24 × 2 = 48`）后 DashScope 开始返回 429，而抽取的单一失败处理把它和"模型答歪了"同等对待：计入 skipped、不重试。这在 429 上是错的 —— 它的语义是"稍后再试"，而且**成片到来**（额度一打穿，接下来几十个调用全是 429），于是一次抽取可能静默丢掉几百个分片，还把它们计进界面上写着「输出校验未通过」的那个数里，与真实原因毫无关系。

改为对 `QUOTA_EXCEEDED` 做指数退避重试（`GRAPH_EXTRACT_RETRY_ON_THROTTLE`，默认 3，填 0 恢复旧行为）。三个设计点：

- **等待发生在抽取线程内部、占着并发槽位** —— 于是限流时整次抽取自然降速到额度能承受的水平，而不是继续对着关闭的门猛敲。这比在外层排队更简单，也不需要额外的信号量。
- **退避带抖动**，不是装饰：所有抽取线程几乎同时被限流，固定退避会把它们一起送回去，精确复现触发 429 的那个突发。
- **只重试 `QUOTA_EXCEEDED`**。鉴权失败、模型不存在、输入过长重试一次也是同样结果，重试只会把一次注定失败的抽取拖长。

结束日志把限流重试次数单独报出（`throttleRetries=`）—— 它是"该不该降 `extract-concurrency`"的唯一依据，混在 skipped 里就分不清丢的分片是模型答歪了还是额度不够。

**另一个容易误判的点**：抽取只覆盖"抽取任务启动那一刻已完成索引"的分片。批量导入时点「重新抽取」，任务只会看到当时已索引完的那部分，界面上的覆盖分片数因此远小于库里的分片总数。这不是缺陷（抽取按启动时的激活版本集合取分片），但**批量导入后应等索引全部完成再抽取**，否则要重跑。

## 5. F4：文档级数据权限的执行点

### 5.1 写路径

`t_kb_doc_acl` 改绑先删后插（`DocumentAclService` 单点）；角色删除时清 `t_kb_doc_acl` 残行（对齐 `detachKnowledgeBase` 的论证）；文档删除（含治理回收站彻底删除）时同样清行。

### 5.2 读路径（三个入口，全部贴数据查）

| 入口 | 裁剪方式 |
|---|---|
| 检索 | `RetrievalIndexContextResolver` 对可见版本集叠加裁剪（活跃集与快照冻结集**两条分支都裁**——发布冻结的是"哪些版本作答"，从不冻结"谁能看见"，发布后改密的文档必须同步从已发布应用消失）：`visibility=RESTRICTED` 的文档，仅当调用者角色集 ∩ 该文档 ACL 非空才保留。**开放 API 调用一律滤掉 RESTRICTED 文档** —— 终端用户没有角色，发布应用不该成为密级旁路 |
| 文档内容读取 | `KbResourceGuard`（M16 交付时名为 `KbScopeGuard`）新增 `requireDocumentContentAccess(docId)`：库范围校验之后叠加 ACL 判定；全范围短路（`unrestrictedKbScope`）**不豁免文档 ACL** —— kb_scope_all 说的是"哪些库"，不回答"库内哪份文档"，超管例外唯一豁免路径是持有 `doc:review` 码（能改密级的人藏不住内容） |
| 列表 | 文档列表不藏行（§3.2 论证），行上带 `restricted` 布尔与"无权查看内容"态；切片列表/预览走内容读取判定 |

- 无缓存：ACL 判定是每文档一次内存集合交集，重查询只多一次按 doc_id 的索引查（`idx_doc`），检索路径按候选文档批量一次查完 —— 过期的密级比慢 5ms 贵得多。

## 6. F5/F6：目录与身份

### 6.1 DirectoryAuthenticator 扩展

`bind` 返回值从 `DirectoryBindResult` 枚举改为 `DirectoryBindOutcome` record（`result` + `List<String> groupDns`，bind 失败时恒空 —— 枚举承载不了每次调用的数据）。`LdapDirectoryAuthenticator` 在 bind 成功且 group-sync.enabled 时，用**用户自身连接**查自己 entry 的 `memberOf`（UPN 绑定经 RootDSE 的 defaultNamingContext 做子树检索，DN 绑定直读，无需服务账号 —— 引一个长期口令只为读自己可见的属性，是纯增的秘密管理负担）；查询失败降级为空组并 error 日志，**不影响登录成功**。

### 6.2 组→角色映射

`role-mappings` 解析成 `Map<归一化DN, roleCode>`；命中的 code 在 `t_kb_role` 中按调用者租户查（查不到 → warn 日志跳过，不阻断）。空映射 + enabled=true → 启动 warn（开了同步却没有映射，多半是配置漏了）。

### 6.3 同步语义（granted_by 的用途）

每次 SSO 登录：算出映射角色集 → 物理删该用户全部 `granted_by=LDAP_SYNC` 行 → 插入新集（标 LDAP_SYNC）→ `principalResolver.evict(username)`。`MANUAL` 行永不触碰 —— 管理员手工授的角色被夜里一次登录悄悄撤掉，是排查不出来的那类事故；反向同理，组里撤人后同步行必须消失，否则同步没有意义。首登默认角色在同步开启时**不再授予**（组就是角色的事实源，再塞一个 VIEWER 进 MANUAL 集等于永远撤不掉）。

## 7. F8：操作审计执行点

- `@AuditedOperation(module, action, targetType)` 注解 + `OperationAuditAspect`（`@Around`）：成功返回后取 `UserContextHolder` 主体、路径变量/返回值中的业务 id（SpEL 取 target id，注解里声明表达式）、`HttpServletRequest` 的 client ip 与 requestId，`@Async` 落库 —— 主体信息在请求线程取完再交给 executor（§M15-4.4 的边界纪律）。
- 失败的写操作**不记**：审计回答"谁改了什么"，改失败什么都没变，全记会让表体积翻几倍而检索价值为零；安全侧的失败尝试已有登录审计与 403 日志。
- 覆盖面：kb / document / governance / annotation / dict / eval / app / apikey / user / role / tenant / system config 的全部写端点（POST/PUT/DELETE），预计 ~90 处注解；`detail` 只存业务 id 与摘要字段，**绝不存请求体原文**（口令、文档内容都从这里过）。
- 保留期清理：复用洞察表清理任务的调度位置，按 `idx_created_at` 批删。

## 8. kb-rag-web 汇总

- **登录页**：`GET /auth/sso/providers` 渲染协议按钮（302 跳转即可，无表单）；挂载时检出 `#sso_token` 存 token 清 hash；LDAP 页签逻辑不变。
- **租户管理页**（`/settings/tenants`，`tenant:manage`）：列表 + 新建/改名/启停；用户管理页对默认租户超管加租户列与移户操作。
- **文档列表**：受限文档行加"受限"Tag；详情页对 403 渲染"无权查看内容"态；可见性编辑入口（`doc:review`）为 Drawer：Radio 继承/受限 + 角色多选。
- **操作审计页**（`/settings/operation-audits`，`audit:read`）：module/username/时间范围筛选 + 详情 Drawer。
- **反馈/洞察页**：channel 筛选（控制台 / 开放接口），META 表加枚举展示。
- `NAV_ENTRIES` 加两项（租户管理、操作审计），仍是单一真源。

## 9. kb-rag-deploy 汇总（F9/F10）

- `application.yml` + `.env.example`：§2 全部新键，分节"企业化（M16）"。
- OpenAPI kb-server.yaml：§3 全部新端点 + schema，版本 **0.16.0-m16**；kb-open.yaml 加 `/knowledge/feedback`。
- 需求文档：工作区 `docs/知识库需求文档.md` 升 **v1.18**（M14 从"契约先行稿"翻为已核实、D17 更新为"RBAC 已交付于 M15、多租户已交付于 M16"、§13.2 权限延后清单翻状态、新增 M16 章节）；deploy `docs/知识库需求文档.md`（事实源）从 v1.14 回补 M10-M16 至同版本 —— 两份从此逐字一致，§13.4 待办 #2/#3 销账。
- CHANGELOG：server / web / deploy 三仓各记 M16 条目。
- **仓库卫生**：删除 `kb-rag-deploy/.env.bak-*`（3 个）、`backup/es-repo/`、`backups/20260726T151111Z/`、`.claude/worktrees/`；`.gitignore` 补 `.env.bak-*`、`backup/`、`backups/`、`.claude/`。

## 10. 升级说明

1. V17 自动建默认租户并把存量行划入；**存量部署升级后行为零变化**（索引名不变、登录不变、可见性全 INHERIT、三协议关闭）。
2. 启动出现 SUPER_ADMIN 收敛 error 日志属预期（每个租户各报一条），按最小权限重排后自然消失。
3. 删除知识库从"只删记录"变为"记录 + 物理索引都删"——依赖死索引做过对照分析的运维习惯需要改变；快照裁剪也开始真正释放磁盘。
4. 开放 API 检索结果可能变少：RESTRICTED 文档被滤出。发布前评估存量文档是否需要收窄密级（默认没有任何文档是 RESTRICTED，不动就无影响）。
5. 组同步开启后，SSO 账号的角色以目录组为准（MANUAL 授的除外）；开启前先配好映射。
6. **图谱抽取默认并发从 2 提到 8**（§4.3）：模型侧调用速率随之上升约四倍，峰值并发 = 抽取任务池上限 2 × 8 = 16。限流额度紧的部署把 `GRAPH_EXTRACT_CONCURRENCY` 调回去即可，正确性不依赖它。`GRAPH_EXTRACT_BATCH_SIZE` 已移除，存量 `.env` 里留着不报错也不生效。
7. 开放 API 反馈端点开始校验 request_id 的应用归属（§3.6）：**只用自己检索返回的 request_id 提交反馈**的集成方不受影响；此前若有跨应用复用 request_id 的用法会被拒为 `APP_ACCESS_DENIED`。控制台调试检索的 request_id 一律不再被该端点接受。
8. **嵌入请求速率上升、同时索引的文档数从 2 变 4**（§4.4）：新增 `EMBEDDING_CONCURRENCY`（默认 4，全局上限），索引池由 `core=2/max=4`（max 因队列 200 深而永不可达，实际并发恒为 2）改为 `core=max=4`。嵌入服务限流紧的部署把 `EMBEDDING_CONCURRENCY` 填 1 即恢复串行；解析服务扛不住 4 路并发的部署需要相应扩 parser 实例。
9. **并发参数改为可配置，默认值一律不变**（§4.5）：新增 `INDEX_CONCURRENCY` / `GRAPH_TASK_CONCURRENCY` / `PARSER_MAX_WORKERS`，取值等于此前的硬编码值，所以**不设任何变量的部署行为完全不变**。唯一的实际行为变化是图谱任务池由 `core=1/max=2`（同 §4.4 ③ 的陷阱，实际恒为 1）改为 `core=max=2`——同时能跑两个图谱重建了，模型侧峰值随之变成 `GRAPH_EXTRACT_CONCURRENCY × 2`。parser 侧 OCR 调用池由固定 2 改为跟随 `PARSER_MAX_WORKERS`，扫描件批量场景不再被卡在 2 页并发。按机器调参见 §4.5，**调大任何并发都要同步扩 `MYSQL_POOL_SIZE`**。
10. **抽取延迟归因与降延迟改造**（§4.6）：抽取延迟 ≈ 输出 token 数 ÷ 模型生成速度，与并发无关（实测 1482 token ÷ 40 token/s = 37 秒/片，与 `1200s × 12 ÷ 385` 的反算吻合）。新增 `GRAPH_EXTRACT_MODEL`（默认空 = 沿用 `CHAT_MODEL`，可换 turbo 档把生成速度翻倍）、`GRAPH_EXTRACT_MAX_ENTITIES`（默认 24，实体与关系共用上限）；`GRAPH_EXTRACT_MAX_TOKENS` **默认 2048 → 3072**（均值就占 2048 的 72%，长尾必然截断、实测 9% 的分片因此整片丢失）。提示词新增数量上限与紧凑 JSON 要求，所以**同一分片抽出的实体/关系可能比升级前少**——换来的是不再有分片因 JSON 截断而整片丢失。图写入的 Chunk MERGE 补上 `kb_id` 谓词以命中复合索引（原先退化为全 Chunk 扫描）。另修 `ModelProviderConfig` 派生抽取/判题 provider 时漏复制 `generateTimeoutMs` 的 bug：字段默认值恰好相同所以从未暴露，但部署方设了 `CHAT_GENERATE_TIMEOUT_MS` 时对这两条链路静默无效。
11. **抽取遇限流（429）改为退避重试**（§4.6）：此前 429 与"模型答歪了"同等对待——计入跳过、不重试，而 429 成片到来（额度一打穿，接下来几十个调用全是 429），一次抽取可能静默丢掉几百个分片。新增 `GRAPH_EXTRACT_RETRY_ON_THROTTLE`（默认 3，填 0 恢复旧行为），指数退避 + 抖动，等待占着并发槽位以自然降速。结束日志新增 `throttleRetries=`。**提高 `GRAPH_EXTRACT_CONCURRENCY` 前先看这个数**——峰值并发 = 它 × `GRAPH_TASK_CONCURRENCY`，撞限流的表现是失败率上升而不是变慢。

### 10.1 开发期排障：V17 checksum 不匹配导致启动失败

**只影响在 M16 开发期中途启动过服务的本地库**。合并后的新部署不会遇到——它会一次性跑完整的 V17。

```
Migration checksum mismatch for migration version 17
-> Applied to database : 1966083435
-> Resolved locally    : -795986924
```

起因是未发布分支上迁移文件被继续修改：本地库应用的是 V17 的中间版本，而工作区是最终版（评审后往 V17 追加了 `t_kb_search_insight.app_id`，即 §3.6 那个越权修复所需的列）。Flyway 用 checksum 保证"已应用的迁移文件没被改过"，于是拒绝启动。

**不要只跑 `flyway repair`**。repair 只更新 checksum 让启动通过，库里缺的列不会补上，启动后一写检索洞察就报 `Unknown column 'app_id'`——那个列是安全修复的落地点，必须真的存在，不能只骗过校验。正确顺序是先补结构、再对齐 checksum：

```bash
docker exec kb-rag-mysql mysql -ukbrag -p<MYSQL_PASSWORD> kb_rag -e "ALTER TABLE t_kb_search_insight ADD COLUMN app_id VARCHAR(64) DEFAULT NULL COMMENT '开放接口调用方应用标识，控制台调试检索为空', ADD KEY idx_request (request_id); UPDATE flyway_schema_history SET checksum = <报错里的 Resolved locally 值> WHERE version = '17';"
```

`checksum` 填**报错信息里的 `Resolved locally`**，不要照抄本文的数值——那是写文档时的值，V17 若再有改动就不同了。回滚点是报错里的 `Applied to database` 值。

若中间版缺的不止这一列（取决于本地库是哪天应用的），用下面这条列出全部差异，逐项补齐后再改 checksum：

```bash
docker exec kb-rag-mysql mysql -ukbrag -p<MYSQL_PASSWORD> kb_rag -N -e "SELECT 'tables', GROUP_CONCAT(table_name) FROM information_schema.tables WHERE table_schema='kb_rag' AND table_name IN ('t_kb_tenant','t_kb_doc_acl','t_kb_operation_audit'); SELECT 'tenant_id_on', GROUP_CONCAT(table_name) FROM information_schema.columns WHERE table_schema='kb_rag' AND column_name='tenant_id'; SELECT 'doc.visibility', COUNT(*) FROM information_schema.columns WHERE table_schema='kb_rag' AND table_name='t_kb_document' AND column_name='visibility'; SELECT 'user_role.granted_by', COUNT(*) FROM information_schema.columns WHERE table_schema='kb_rag' AND table_name='t_kb_user_role' AND column_name='granted_by'; SELECT 'feedback.channel+end_user', COUNT(*) FROM information_schema.columns WHERE table_schema='kb_rag' AND table_name='t_kb_retrieval_feedback' AND column_name IN ('channel','end_user_id'); SELECT 'insight.app_id', COUNT(*) FROM information_schema.columns WHERE table_schema='kb_rag' AND table_name='t_kb_search_insight' AND column_name='app_id'; SELECT 'idx_request', COUNT(*) FROM information_schema.statistics WHERE table_schema='kb_rag' AND table_name='t_kb_search_insight' AND index_name='idx_request'; SELECT 'perm tenant:manage', COUNT(*) FROM t_kb_permission WHERE code='tenant:manage';"
```

最终版 V17 跑完应该是：三张新表齐全、八张表带 `tenant_id`（六张由 V17 补列，`t_kb_tenant` 与 `t_kb_operation_audit` 建表自带）、其余每项为 1，`feedback` 那项为 2。空库或无业务数据的开发库直接 `DROP DATABASE kb_rag; CREATE DATABASE kb_rag;` 重跑全部迁移更省事——**有业务数据的库不要这么做**。

## 11. 单测清单（离线，精确断言）

- **F1**：删库置 PENDING_CLEANUP + 建 CLEANUP 任务；执行器幂等（indexExists=false 不报错）；引擎异常任务留存可重试；快照存量 PENDING_CLEANUP 行被同一执行器收走。
- **F2**：0/1 个超管不告警，2 个告警且逐一点名；**两个租户各有同 code 超管角色时各报一条且带 tenantId**（回归 `limit 1` 盲区）。
- **F4.3**：barrier 只在凑满 `extract-concurrency` 个模型调用时才放行（串行或并发不足则超时并计入 skipped，断言 skipped=0）；图写入并发峰值恒为 1 而抽取峰值 > 1；进度按列更新且次数 ≤ 分片数。
- **F4.4**：嵌入批次并发（barrier 凑不齐即超时）；状态写入次数 = 批次数而非分片数且不走 `updateById`，内存状态仍为 DONE；单批次不进线程池；一批失败即整次失败且抛出的仍是原 `BizException`（错误码可读）；零 Key 模式不调 provider 也不落库。
- **F4.6**：配置的数量上限进入提示词（实体与关系两句都带同一数字），注入防护那句不被挤掉；荒谬上限（0）夹到下限 4 而不是照搬——否则提示词变成"什么都别抽"、抽取静默产出空图。
- **F4.6 限流**：429 重试后仍能写入该分片（provider 被调两次、图写入发生）；重试预算耗尽即放弃（绝不无限重试）；`AUTH_FAILED` 只调一次——重试一次也是同样结果。
- **F3**：TenantLineHandler 无主体跳过拼接、忽略清单命中；建租户复制五内置角色；停用租户后 resolve 抛 401；停用 DEFAULT 被拒；IndexNaming 默认租户名不变（回归红线）、非默认租户带段、tenant 段归一化。
- **F4**：`DocumentAclService.trimRestricted` 叠加 ACL 正负例（含快照冻结集分支）；开放 API 滤 RESTRICTED；`requireDocumentContentAccess` 四象限（范围内/外 × 有/无 ACL）+ doc:review 豁免 + kb_scope_all 不豁免；角色删除/文档删除清 ACL 残行。
- **F5**：映射解析（大小写、空白、非法条目跳过）；LDAP_SYNC 全量替换且 MANUAL 不动；组查询失败不阻断登录；同步开启时首登不授默认角色。
- **F6**：state/RelayState 一次性与过期；OIDC id_token 验签失败/iss 不符/aud 不符各拒；SAML 签名无效/过期/Audience 不符各拒 + XXE payload 被拒；CAS 响应解析正负例；三协议 SERVICE_UNAVAILABLE 不计锁定；LOCAL 账号被 SSO 命中拒于 WRONG_LOGIN_MODE。
- **F7**：request_id 反查补齐 kb_id/query；无效 request_id 拒；channel 落 OPEN_API；comment 截断；**别人应用的 request_id 拒（app 归属）**、**跨库 chunk_id 拒**、分片已删除仍接受但不落 doc_id。
- **F8**：切面成功记/失败不记；detail 不含请求体；@Async 主体在请求线程捕获；保留期批删。
- **回归红线**：既有单测零修改通过；`IndexNamingTest` 既有断言不改一字（默认租户名不变的机器验证）。

## 12. 验收清单（实现完成后用户自测）

1. 升级后旧库检索/登录一切照旧；启动日志出现 SUPER_ADMIN 收敛 warn。
2. 删一个知识库 → ES `_cat/indices`（或 Qdrant collections）里该库物理索引消失。
3. 建租户 T2 + T2 超管账号 → 该账号登录只见 T2 的空世界；默认租户的库、用户、角色一概不可见；反向亦然；T2 建库后索引名带租户段。
4. 一份文档设 RESTRICTED 只授角色 A → 角色 B 的账号检索不到它、详情 403、列表见"受限"标记；开放 API 检索不返回它；授权改回 INHERIT 立即恢复。
5. 开 OIDC 指向 IdP（如 Keycloak）→ 登录页出现按钮 → 跳转认证回来直接进控制台，用户管理页出现 source=OIDC 新行；SAML、CAS 同样各走一遍。
6. 开组同步 + 映射两个组 → 域账号登录后角色 = 映射结果；目录撤组再登录角色消失；手工授的角色不受影响。
7. 开放 API 用有效 request_id 提交反馈 → 控制台反馈列表出现渠道=开放接口的行，可转评测用例。
8. 任意管理台写操作后 → 操作审计页出现"谁、何时、对什么、做了什么"的行。
9. deploy 仓不再有 `.env.bak-*`、`backup/`、`backups/`；两份需求文档 diff 为空。
10. `mvn -B -ntp verify` 全绿；`npm run build` + `npm run lint` 全绿。
