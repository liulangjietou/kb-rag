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

- MyBatis-Plus `TenantLineInnerInterceptor` 注册在分页插件**之前**（官方要求，租户条件必须进 count 语句）；`TenantLineHandler.getTenantId()` 取 `UserContextHolder` 的租户，无控制台主体（开放 API、后台任务线程）时**返回 null 并跳过拼接** —— 后台任务按业务 id 精确定位行，API Key 已被应用版本限定范围，再拼租户条件需要的上下文根本不在线程里。
- **忽略清单**（`ignoreTable`）：除 §1.2 六张根聚合表外全部忽略。从属表靠父表裁剪；`t_kb_tenant`、`t_kb_permission`、`t_kb_auth_token`、两张登录/操作审计表、META 表、flyway 表天然全局。
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

## 5. F4：文档级数据权限的执行点

### 5.1 写路径

`t_kb_doc_acl` 改绑先删后插（`DocumentAclService` 单点）；角色删除时清 `t_kb_doc_acl` 残行（对齐 `detachKnowledgeBase` 的论证）；文档删除（含治理回收站彻底删除）时同样清行。

### 5.2 读路径（三个入口，全部贴数据查）

| 入口 | 裁剪方式 |
|---|---|
| 检索 | `RetrievalIndexContextResolver` 对可见版本集叠加裁剪（活跃集与快照冻结集**两条分支都裁**——发布冻结的是"哪些版本作答"，从不冻结"谁能看见"，发布后改密的文档必须同步从已发布应用消失）：`visibility=RESTRICTED` 的文档，仅当调用者角色集 ∩ 该文档 ACL 非空才保留。**开放 API 调用一律滤掉 RESTRICTED 文档** —— 终端用户没有角色，发布应用不该成为密级旁路 |
| 文档内容读取 | `KbScopeGuard` 新增 `requireDocumentContentAccess(docId)`：库范围校验之后叠加 ACL 判定；全范围短路（`unrestrictedKbScope`）**不豁免文档 ACL** —— kb_scope_all 说的是"哪些库"，不回答"库内哪份文档"，超管例外唯一豁免路径是持有 `doc:review` 码（能改密级的人藏不住内容） |
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

## 11. 单测清单（离线，精确断言）

- **F1**：删库置 PENDING_CLEANUP + 建 CLEANUP 任务；执行器幂等（indexExists=false 不报错）；引擎异常任务留存可重试；快照存量 PENDING_CLEANUP 行被同一执行器收走。
- **F2**：0/1 个超管不告警，2 个告警且逐一点名；**两个租户各有同 code 超管角色时各报一条且带 tenantId**（回归 `limit 1` 盲区）。
- **F4.3**：barrier 只在凑满 `extract-concurrency` 个模型调用时才放行（串行或并发不足则超时并计入 skipped，断言 skipped=0）；图写入并发峰值恒为 1 而抽取峰值 > 1；进度按列更新且次数 ≤ 分片数。
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
