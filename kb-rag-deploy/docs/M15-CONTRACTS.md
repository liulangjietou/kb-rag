# M15 开发契约（权限体系：功能权限 + 知识库数据权限 + 单点登录 · 增量于 M1-M14 契约）

> 需求依据：知识库需求文档 D17 延后决策（"RBAC 与多租户后续里程碑"）落地；单点登录实现参考既有内网系统 `LdapAuthService`（JNDI simple bind）。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带上下文、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；面向用户文案中文；web 枚举展示走 META 表。
> 用户已确认的三项选型：①权限粒度 = **功能权限 + 知识库级数据权限 + 检索时裁剪**（最深档）；②域账号**首登自动建号**并授予可配置默认角色；③平台账号**仅管理员在用户管理页创建**，无自助注册。

## 0. 范围与边界

**本期做**：

| # | 特性 | 一句话定义 |
|---|---|---|
| F1 | 用户与角色模型 | `t_kb_admin_user` 长成用户表 + 权限目录 / 角色 / 三张关联表，一人多角色，角色持权限码与库范围 |
| F2 | 功能权限执行 | `@RequiresPermission`（any-of 语义）+ `PermissionInterceptor`，103 处注解覆盖 24 个 Controller |
| F3 | 知识库级数据权限 | `AccessGuard.requireKbAccess`（路径带 kbId）+ `KbScopeGuard` 九个反查方法（路径只带业务 id） |
| F4 | 检索与列表裁剪 | KB 列表按可见集裁剪；检索、图谱、洞察、反馈、评测、应用预览逐入口校验命名的库 |
| F5 | 单点登录 | LDAP simple bind + 首登 JIT 建号授默认角色；与平台账号双入口，互不串用 |
| F6 | 管理台 | 登录页双方式、菜单与路由按权限、用户管理页、角色管理页、写操作按钮级收口 |

**本期不做**：多租户（库归属仍是全局资源，范围靠角色授权而非 owner 列）；权限到"文档/切片"粒度（数据权限止于知识库）；LDAP 组织架构同步（只做认证 bind，不读 group 反授角色）；SAML/OIDC/CAS（`DirectoryAuthenticator` 是 port，换实现即可，本期只有 LDAP 实现）；开放 API 的 API Key 体系照旧（M6 的 `ApiKeyPrincipal` 与控制台账号是两套主体，见 §5.4）。

**兼容红线**：升级即用，不需要人工授权动作 —— 存量账号（含 `admin`）一律提权为 `SUPER_ADMIN`，否则升级后**没人能进用户管理页发第一个角色**。所有新端点为纯新增；`@RequiresPermission` 缺省（未注解）= 放行，故未覆盖的既有端点行为零变化。

## 1. 数据模型（Flyway V16__rbac.sql，四张新表 + 一张表升级）

### 1.1 三条设计取舍（写在迁移脚本文件头）

1. **不新建 user 表**，让 `t_kb_admin_user` 长成用户表 —— 会话令牌表（M11 `t_kb_auth_token`）与登录审计表都以 `username` 为外键语义，另起一张表会立刻制造两个"当前用户"。
2. **权限码落库**而非只写在 Java 常量里 —— 角色管理页要按模块分组展示中文名，这是它的数据源；常量类 `PermissionCodes` 只保证编译期不写错字面量。
3. **数据范围挂角色不挂用户** —— 一个人的可见库 = 其全部角色的并集；任一角色 `kb_scope_all=1` 即全库。挂用户会让"批量调整一类人的可见范围"退化成逐人操作。

### 1.2 表定义

| 表 | 定义 |
|---|---|
| t_kb_admin_user（升级） | `password_hash` 改**可空**（域账号无本地口令）；新增 `user_id` VARCHAR(64) UK（`usr_` 前缀）、`display_name` VARCHAR(64) NULL、`email` VARCHAR(128) NULL、`source` VARCHAR(16) NOT NULL DEFAULT 'LOCAL'（LOCAL/LDAP）、`status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED'（ENABLED/DISABLED）；存量行 `user_id` 用 `CONCAT('usr_', SUBSTRING(REPLACE(UUID(),'-',''),1,16))` 回填 |
| t_kb_permission | `code` VARCHAR(64) UK、`name` VARCHAR(64)、`module` VARCHAR(32)、`module_name` VARCHAR(32)、`sort_order` INT；KEY `idx_module(module)`。权限目录，只读种子数据 |
| t_kb_role | `role_id` VARCHAR(64) UK（`role_` 前缀）、`code` VARCHAR(64) UK、`name` VARCHAR(64)、`description` VARCHAR(256) NULL、`builtin` TINYINT DEFAULT 0、`kb_scope_all` TINYINT DEFAULT 0 |
| t_kb_role_permission | `role_id` + `permission_code`，KEY `idx_role(role_id)` |
| t_kb_user_role | `user_id` + `role_id`，KEY `idx_user(user_id)`、`idx_role(role_id)` |
| t_kb_role_kb | `role_id` + `kb_id`，KEY `idx_role(role_id)`、`idx_kb(kb_id)` |

- **三张关联表故意不设唯一键**：改绑一律"先按 role_id/user_id 物理删旧行再插新行"（`RoleService` / `UserService` 单点），唯一键只会在这种全量替换语义下制造无意义的冲突处理分支。
- 知识库删除时 `RoleService.detachKnowledgeBase(kbId)` 清 `t_kb_role_kb` 残行 —— 范围按业务 id 命名，残行会让死库回到角色编辑器，且被该角色下一次保存重新创建。

### 1.3 权限码目录（18 码 / 6 模块）

| 模块 | 权限码 |
|---|---|
| KB 知识库 | `kb:read`、`kb:write`、`kb:delete`、`doc:write`、`doc:review` |
| RETRIEVAL 检索 | `search:debug`、`feedback:manage` |
| EVAL 评测 | `eval:read`、`eval:write`、`eval:run` |
| APP 应用 | `app:read`、`app:write`、`app:release` |
| OPENAPI 开放接口 | `apikey:manage`、`audit:read` |
| SYSTEM 系统 | `system:config`、`user:manage`、`role:manage` |

### 1.4 内置角色矩阵（`builtin=1`、`kb_scope_all=1`）

| code | 名称 | 权限码 |
|---|---|---|
| SUPER_ADMIN | 超级管理员 | 全部 18 码 |
| KB_ADMIN | 知识库管理员 | 除 `user:manage`、`role:manage` 外全部 |
| EDITOR | 内容编辑 | `kb:read`、`doc:write`、`search:debug`、`eval:read`、`app:read` |
| REVIEWER | 内容审核 | `kb:read`、`doc:review`、`search:debug`、`feedback:manage`、`eval:read` |
| VIEWER | 只读访客 | `kb:read`、`search:debug`、`eval:read`、`app:read` |

- 内置角色**可改授权、可改名，但 code 不可改、行不可删**：种子脚本、后续升级脚本与首登建号路径都按 code 指名。
- 五个内置角色 `role_id` 在迁移脚本里是**字面量**（`role_superadmin000` / `role_kbadmin00000` / `role_editor000000` / `role_reviewer0000` / `role_viewer000000`）—— 后续脚本要引用它们，随机 id 会让升级脚本无从下手。

## 2. 配置键（KbProperties.Auth）

| 键 | 环境变量 | 默认 | 语义 |
|---|---|---|---|
| kb.auth.token-ttl-hours | AUTH_TOKEN_TTL_HOURS | 24 | 会话令牌有效期（M11 既有） |
| kb.auth.max-failed-attempts | AUTH_MAX_FAILED_ATTEMPTS | 5 | 锁定阈值，账号维度与来源 IP 维度各计一次 |
| kb.auth.lock-minutes | AUTH_LOCK_MINUTES | 15 | 锁定窗口 |
| kb.auth.bootstrap-username | AUTH_BOOTSTRAP_USERNAME | admin | 首次启动自动建号的管理员 |
| kb.auth.ldap.enabled | AUTH_LDAP_ENABLED | false | 关闭时登录页不出现单点登录页签 |
| kb.auth.ldap.url | AUTH_LDAP_URL | 空 | 如 `ldap://dc.corp.example.com:389`；生产建议 `ldaps`，simple bind 的口令是明文过线的 |
| kb.auth.ldap.domain-suffix | AUTH_LDAP_DOMAIN_SUFFIX | 空 | 拼到登录名后构成 bind principal，如 `@corp.example.com` |
| kb.auth.ldap.connect-timeout-ms | AUTH_LDAP_CONNECT_TIMEOUT_MS | 3000 | 域控不可达时的失败上限 |
| kb.auth.ldap.read-timeout-ms | AUTH_LDAP_READ_TIMEOUT_MS | 5000 | 同上 |
| kb.auth.ldap.default-role-code | AUTH_LDAP_DEFAULT_ROLE_CODE | VIEWER | 域账号首登被授予的角色 |

- **`enabled=false` 是默认**：没有域控的部署必须仍能用平台账号登录；`GET /auth/sso-available` 的答案就是这个开关（叠加 url/suffix 非空），登录页据此决定是否渲染页签 —— 摆一个只会报"域账号认证服务未配置"的入口比不摆更糟。
- **默认角色只读**：放宽一档就等于给每个持域账号的人开了写权限；置空则认证成功的人落到空控制台再来提单 —— 可选，但那是运维选择而非默认。

## 3. REST 契约

### 3.1 认证（AuthController，`/api/v1/auth`，无功能权限码）

| 端点 | 说明 |
|---|---|
| POST /login | `{username, password, mode}`，mode ∈ `LOCAL`/`SSO`（非法值 → INVALID_PARAM `mode 仅支持 LOCAL 或 SSO`）→ `{token, must_change_password}` |
| GET /sso-available | **免认证**（页面要在无会话时渲染）→ `{sso_available}` |
| POST /change-password | 域账号调用 → INVALID_PARAM"口令由域管理"；成功后吊销该用户全部会话 |
| GET /me | 账号信息 + 本次会话的 `permissions`/`roles`/`kb_scope_all`/`kb_ids` |
| POST /logout | 吊销当前令牌 |

### 3.2 用户（UserController，`/api/v1/users`，类级 `user:manage`）

`GET ?keyword=&status=&source=&page=&size=`、`GET /{userId}`、`POST`（`{username, display_name?, email?, password, role_ids}`）、`PUT /{userId}`（仅 display_name/email）、`PUT /{userId}/status`、`PUT /{userId}/roles`、`POST /{userId}/reset-password`、`DELETE /{userId}`。

- 口令最短 8 位（`UserService.MIN_PASSWORD_LENGTH`）；建号与重置密码均置 `must_change_password=1`。
- 域账号无 `password_hash`，重置密码端点对其拒绝，管理台也不给入口 —— 凭据在域里，本地轮换只会造出第二个被静默忽略的秘密。
- **停用与删除自己被服务端拒绝**（`requireNotSelf`），管理台同步不给入口 —— 把自己锁在控制台外没有恢复路径。
- `username` 不可改：会话令牌与每一行审计都写在它上面，改名会让账号与自己的历史失联。
- 停用即刻吊销该账号全部会话（`tokenStore.revokeAll`）；否则运维锁人之后账号还能再工作一整个令牌有效期。

### 3.3 角色（RoleController，`/api/v1/roles`，类级 `role:manage`）

`GET`（方法级放宽为 `{role:manage, user:manage}` —— 用户管理页要列角色供分配）、`GET /permissions`（权限目录，按 module + sort_order）、`GET /{roleId}`、`POST`、`PUT /{roleId}`、`DELETE /{roleId}`。

- `POST/PUT` body：`{code, name, description?, kb_scope_all, kb_ids, permission_codes}`；`kb_scope_all=true` 时 `kb_ids` 必须为空数组（保留旧集合会在下次收窄时悄悄复活）。
- `builtin=1` 的角色：`code` 不可改、`DELETE` 拒绝。
- 任何角色定义或库范围变更 → `PrincipalResolver.evictAll()`。

### 3.4 错误语义

| 场景 | 结果 |
|---|---|
| 无会话 / 会话失效 / 账号已停用 | 401 `UNAUTHORIZED` |
| 缺功能权限码 | 403 `FORBIDDEN`，message `permission required: a or b` |
| 库不在数据范围内 | 403 `FORBIDDEN`，message `knowledge base outside your data scope: {kbId}` |

**为什么是 403 不是 404**：藏在 404 后面泄露更少，但会让每一张"我看不到这个库"的工单都与坏链接无法区分；库名不是秘密，库的内容才是。（`AccessGuard` Javadoc 同款论证。）

## 4. 两层授权的执行点

### 4.1 功能权限：声明式，web 层拦一次

`@RequiresPermission(String[] value())`，**any-of 语义**（持任一码即过）。`PermissionInterceptor` 注册在 `AuthInterceptor` 之后（后者绑定它要读的主体）：方法级注解优先于类级；**无注解或空数组 = 放行**（认证已完成，合法地"不需要更多"的端点就是会话自身那几个）；`UserContextHolder.get() == null` 抛 unauthorized 并注明"这说明某条受护路径漏了拦截器注册，不是调用方作恶"。

覆盖面：24 个 Controller、103 处注解、112 次权限码引用，18 个码全部被使用。类级声明（整个控制器同一码）：`ApiAuditLogController`→`audit:read`、`ApiKeyController`→`apikey:manage`、`ChunkAnnotationController`→`doc:write`、`IkDictController`→`system:config`、`SearchInsightController`→`{feedback:manage, audit:read}`、`UserController`→`user:manage`、`RoleController`→`role:manage`。

### 4.2 数据权限：命令式，贴着数据查

功能权限无法回答"这个具体的库在不在你范围内"——那要读路径变量或请求体，提不进注解。两种形态：

| 形态 | 用法 | 落点 |
|---|---|---|
| 路径带 `{kbId}` | `AccessGuard.requireKbAccess(kbId)` 静态调用 | KnowledgeBase(8) / Document(2) / DocumentGovernance(1) / Search(1) / SearchInsight(2) / Graph(5) / EvalDataset(3) / ExtSource(2) / RetrievalFeedback(2) |
| 路径只带业务 id | `KbScopeGuard.requireXxxAccess(id)` 反查所属库再判 | document / chunk / annotation / dataset / case / run / extSource / webSource / feedback 九个 |

- `KbScopeGuard` 每个方法首行 `if (AccessGuard.unrestrictedKbScope()) return;` —— 全范围调用者（管理员、API Key 路径）连反查那一次查询都不付。
- 多库一次校验用 `requireKbAccess(Set)`：**全有或全无**。静默丢掉不可见的库会让多库检索答出一个"看起来完整实则不然"的结果。

### 4.3 列表与检索裁剪

- `KnowledgeBaseService.list()` 按可见集裁剪，且**在 service 而不是 controller** —— 这个列表喂遍控制台所有知识库选择器，范围只含三个库的人不该在下拉里看到第四个然后被拒。
- 范围为空 → 返回空列表而不是全部：忘授范围应该表现为"这个角色什么都干不了"（可见、可修），而不是"这个角色悄悄能看全部"。

### 4.4 @Async 边界（易错点，务必遵守）

`UserContextHolder` 是普通 ThreadLocal，`AsyncConfig` 的 TaskDecorator **只传 requestId**。凡走 executor 的链路，权限校验必须留在请求线程：应用预览流式接口因此把校验提成独立方法 `KnowledgeApiService.requirePreviewKbAccess(appId, appVersionId)`，由 Controller 在请求线程调用。写在 `preview` 首行会找不到用户并**放行整条调用**，且那正是控制台默认走的传输。

### 4.5 权限缓存

`PrincipalResolver` 写透式进程内缓存（`ConcurrentHashMap`，key = username）。四次查询（roles / role rows / grants / kb scopes）不该由每次控制台调用承担，而这些行一个月改动几次。失效**刻意粗暴**：改角色定义清全表（`evictAll`）而不去算谁持有它 —— 算准要多一次查询，而过期的授权是安全缺陷，清空只是四次查询。单实例部署假设与 M11 `TokenStore` 一致。

## 5. 单点登录（F5，参考 LdapAuthService）

### 5.1 端口与实现

- kb-domain port `DirectoryAuthenticator`：`boolean available()`、`DirectoryBindResult bind(username, password)`。
- kb-infrastructure `LdapDirectoryAuthenticator`：裸 JNDI（`InitialDirContext` + `SECURITY_AUTHENTICATION=simple`）而非 Spring LDAP —— 只需要一次 bind，JNDI 随 JDK 发布，单点登录因此不给这个部署添任何依赖。**靠异常类型区分两种失败**：`AuthenticationException` → `INVALID_CREDENTIALS`（域控答了且说不），其余 `NamingException` → `SERVICE_UNAVAILABLE`（超时/不可达/协议故障）。这正是参考实现的核心判据，也是下一条的前提。
- **空口令直接判 INVALID_CREDENTIALS，不发给域控** —— LDAP 把空凭据读作匿名 bind，很多目录会接受，那会把一个空输入框变成一次成功登录。
- principal 拼接：用户已经自己带了 `@` 就不再追加后缀 —— 从邮件客户端复制地址很常见，双后缀会以"口令错误"的面目失败。
- AD 对口令错、账号禁用、口令过期返回不同 data 码，**不做区分** —— 告诉调用方是哪一种就等于确认了账号存在。
- 日志：bind 成功/被拒 info（带 principal，**不带口令**），bind 失败与未配置 error（带 url）。

### 5.2 锁定策略的例外

`DIRECTORY_UNAVAILABLE` 记审计但**不计入锁定计数**（`countFailures` 显式 `.ne(reason, DIRECTORY_UNAVAILABLE)`）。否则一次域控抖动就会把所有重试过的人一起锁死 —— 这是"口令错"与"服务不可用"必须分成两个 `LoginResult` 的实际原因。

### 5.3 两个入口互不串用

| 情形 | 结果 |
|---|---|
| 平台账号走 SSO | 拒，`WRONG_LOGIN_MODE`，**在 bind 之前判** —— 否则一个与本地管理员同名的域账号会继承它 |
| 域账号走 LOCAL | 拒，`WRONG_LOGIN_MODE`，提示"该账号通过单点登录" |
| 已停用账号走 SSO | 拒，**同样在 bind 之前判**，停用账号不能被用来探测域控 |
| 登录名归一化 | 小写 + 去掉用户粘贴的域后缀（`Zhang@corp.com` 与 `zhang` 是一个人），否则同一位同事会被建出多个账号 |

### 5.4 首登 JIT 建号

bind 成功且库里无此人 → `UserService.provisionDirectoryUser(username)`：`source=LDAP`、`password_hash=null`、`must_change_password=0`、授 `ldap.default-role-code` 对应角色（该 code 不存在则建号不授角色并 error 日志，不阻断登录）。随后 `principalResolver.evict(username)` —— 新建账号没有缓存，回访账号可能在离开期间被改过授权。

**开放 API 主体不变**：`ApiKeyPrincipal`（M6）与控制台账号是两套主体，`AccessGuard.unrestrictedKbScope()` 在无控制台调用者时返回 `true` —— 出站调用已被它命中的应用版本限定范围，再拿一个不存在的控制台范围去裁剪会把结果全滤掉。

## 6. kb-rag-web 汇总（F6）

- **权限单一真源** `layout/navigation.tsx` 的 `NAV_ENTRIES`（key/label/icon/anyOf），同时驱动侧边菜单与 `landingPath(canAny)` 落地重定向；菜单高亮改"最长可见前缀优先"，兜底走同一个 `landingPath` 而非硬编码 `/kb`。
- **登录页**：`GET /auth/sso-available` 决定是否渲染 Tabs（单点登录 / 平台账号）；`mode` 随凭据一起送而**不靠用户名猜** —— 同一人可能同时有域账号与本地账号，域口令绝不能拿去比本地哈希。探测失败按"无域控"静默处理（`request.ts` 的 `SILENT_PATHS`）：这是页面自作主张发起的探测，弹红条等于为访客没请求过的事责怪他。
- **路由**：`RequirePermission({anyOf})` 拒绝时**原地渲染 403** 而不重定向 —— 账号确实主动请求了这个屏幕，弹走会静默吞掉别人发来的 URL，且会与从同一权限集算出目标的根重定向互相踢。`/no-access` 承接"认证成功但无任何权限"（典型即域账号首登拿到未授权的角色）。
- **用户管理页**：keyword/status/source 三筛选 + 建号/编辑/角色分配/重置密码/启停/删除；域账号不给重置密码入口；自己的账号不给停用与删除（换成 Tooltip 说明）—— 把自己锁在控制台外没有恢复路径。
- **角色管理页**：Drawer 编辑器，`code` 校验 `^[A-Z][A-Z0-9_]*$`，库范围 Radio（全部 / 指定），权限码按 module 分组 `Checkbox.Group`；builtin 禁改 code、禁删。
- **写操作按钮级收口**：`kb:write` 收知识库新建与删除、`app:write` 收应用新建与删除；空态文案按有无写权限分叉。服务端每次再查 —— 隐藏一个路由只是省下一次必答 403 的往返，不是数据安全的依据。

## 7. kb-rag-deploy 汇总

- `application.yml` 新增 `kb.auth.ldap` 六项（此前 `KbProperties.Auth.Ldap` 已存在但**无法经环境变量注入**，本期补齐）。
- `.env.example` 新增"权限体系与单点登录（M15）"分节，10 个变量。
- OpenAPI kb-server.yaml：`/auth/sso-available`、`/users` 8 端点、`/roles` 6 端点 + 相应 schema；版本升 **0.15.0-m15**。

## 8. 升级说明

1. **存量账号全部提权 SUPER_ADMIN**（迁移脚本内完成）—— 不然升级后没人能进用户管理页发第一个角色。生产升级后第一件事：按最小权限重排这些账号。
2. `t_kb_admin_user.password_hash` 变为可空。任何依赖"该列非空"的外部脚本需同步调整。
3. 未打 `@RequiresPermission` 的既有端点仍然放行（仅需登录）。新增 Controller **必须显式声明**，缺省放行是为兼容存量而非推荐姿势。
4. LDAP 关闭时行为与升级前完全一致：单一登录表单、平台账号、BCrypt。

## 9. 单测清单（离线，精确断言）

- **F1/F2**：`PermissionInterceptor` 方法级覆盖类级、无注解放行、any-of 任一命中即过、缺码抛 403 且 message 含全部码、无主体抛 401。
- **F3**：`AccessGuard.requireKbAccess` 单库/多库全有或全无；`KbScopeGuard` 九个方法各正负例 + 全范围短路不查库。
- **F4**：`KnowledgeBaseService.list()` 全范围返回全部、受限返回交集、范围空返回空列表；`requirePreviewKbAccess` 在未配置库的版本上不拒（交由预览自身报缺失）。
- **F5**：bind 三态映射（成功/口令错/不可达）；`DIRECTORY_UNAVAILABLE` 不计锁定而 `BAD_PASSWORD` 计；两个入口互不串用四种情形；登录名归一化（大小写、域后缀）；首登建号落 `source=LDAP` + 授默认角色 + 默认角色 code 不存在时不阻断登录。
- **权限缓存**：`resolve` 命中缓存不重查；`evict`/`evictAll` 生效；账号停用后 `resolve` 抛 401（不等令牌过期）。
- **回归红线**：既有单测零修改通过（未注解端点放行 = 兼容承诺的机器验证）。

## 10. 验收清单（实现完成后用户自测）

1. 升级后用存量 `admin` 登录 → 菜单全开 → 用户管理页可见。
2. 建一个 `VIEWER` 账号（范围指定单库）→ 登录后菜单只剩知识库与检索调试；知识库列表只有那一个库；直接敲 `/settings/users` 得到原地 403；调 `POST /api/v1/kb` 得到 403 `permission required: kb:write`。
3. 该账号对不可见库的 `GET /api/v1/kb/{kbId}/documents` 得到 403 `knowledge base outside your data scope`。
4. 开 `AUTH_LDAP_ENABLED` 指向域控 → 登录页出现"单点登录"页签 → 域账号首登成功且用户管理页出现 `source=域账号` 的新行、角色为默认角色；该账号在平台账号页签登录被拒。
5. 关 `AUTH_LDAP_ENABLED` → 登录页只有单一表单，平台账号登录不受影响。
6. 停掉域控再用 SSO 登录 → 提示"域账号认证服务暂时不可用"，连试 6 次后**平台账号仍可正常登录**（不可达不计锁定）。
7. 改某角色的权限码 → 持该角色的会话**下一次请求**即生效（无需重登）。
8. 删除一个知识库 → 角色编辑器里该库消失，且再保存该角色不会把它带回来。
9. `mvn -B -ntp verify` 全绿；`npm run build` + `npm run lint` 全绿。
