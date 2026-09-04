# M26 开发契约：邮箱注册、管理员审核与企业知识首页

> 状态：已实现并与服务端、控制台、Flyway V26 和 OpenAPI 对齐（2026-09-04）。

## 0. 目标与边界

M26 为没有平台账号的用户提供邮箱自助申请，但“证明邮箱归属”不等于“获得系统权限”。公开端只负责
滑块校验、邮箱验证码、一次性注册票据和待审核申请；正式账号、租户归属与角色只由管理员审核事务创建。
申请成功不会签发 token，也不会在 `t_kb_admin_user` 留下一条待激活账号，因而审核前不存在绕过审核登录的
账号生命周期。

本期不做邮件域名白名单、邀请制、跨实例共享限流或 SMTP 精确一次投递。公网多实例部署仍应在网关/WAF
配置总量限流；审核结果邮件采用可恢复的 at-least-once outbox，极端崩溃窗口允许重复通知。

## 1. 公开注册链路与状态机

只有三个精确 POST 路径免登录，路径变体和审核接口不进入公开白名单：

1. `POST /api/v1/registrations/verification-code`：先原子消费与客户端 IP、User-Agent 绑定的滑块 proof，
   再按邮箱、IP 和单实例全局三层限流发六位验证码。
2. `POST /api/v1/registrations/verify-email`：按邮箱锁定验证行，以常量时间比较验证码 HMAC；成功后仅返回
   一次明文注册票据。
3. `POST /api/v1/registrations`：请求不再接收邮箱，服务端只从票据摘要锁定的验证行取得已验证邮箱；保存
   `PENDING` 申请与消费票据处在同一事务。浏览器为一次提交生成稳定 `client_submission_id` UUID，响应
   丢失后以同标识和同票据重试会返回原回执；同标识换票据被拒绝，且任何成功都不创建会话。

邮箱会去首尾空白并统一小写，语法长度上限 254。`t_kb_email_verification` 每邮箱唯一；验证码 challenge
与注册 ticket 是同一行上的两条正交生命周期：

```text
challenge: NONE -> ISSUING -> DELIVERED -> NONE
           （code_hmac + expires_at + attempts 只在当前 challenge 存在时保存）
ticket:    无 -> ticket_hash + ticket_expires_at -> CONSUMED

聚合 status:
  无有效 ticket 时，challenge 存在为 ISSUED；过期/耗尽为 INVALIDATED
  有有效 ticket 时保持 VERIFIED，可同时存在一个新的 challenge
```

- `ISSUED` 只保存 `HMAC-SHA-256(verificationId + email + code)`、剩余次数、过期/重发时间、来源 IP
  HMAC 与独立的 `code_delivery_status`；验证码明文只存在于当前调用栈和即时 SMTP 邮件中。
- 发码短事务先把 challenge 保存为 `ISSUING`，事务提交后才执行 SMTP；成功后用 verificationId 与 code
  HMAC 双重 CAS 推进为 `DELIVERED`。只有 `DELIVERED` 可校验或在冷却期复用；首封邮件未确认时的并发请求
  统一返回 429，不会发送“请使用最近邮件”的错误成功提示。SMTP 失败或确认 CAS 失败则精确撤销本次码，
  不会覆盖并行轮换后的新码或已有有效票据。
- 错误验证码在独立事务中先持久扣减次数，再返回统一无效响应，业务异常不能把次数回滚。没有旧票据时，
  耗尽/过期会进入 `INVALIDATED`；仍有有效旧票据时只清 challenge 并保持 `VERIFIED`。
- 新验证码成功才清除 `code_hmac` 并原子替换票据 SHA-256 与过期时间；因此页面刷新后可重新发码恢复，
  匿名重发或错误新码不能撤销浏览器仍持有的旧票据，而新码成功后旧票据才失效。
- `VERIFIED` 票据只能由条件更新推进为 `CONSUMED`；消费同时清除票据摘要和任何并行 challenge，数据库
  不保存票据明文。
- 邮箱首次发码使用唯一键原子初始化后再锁行，多实例竞争不会互相覆盖冷却中或交付中的验证码；进程在
  SMTP 完成前退出留下的 `ISSUING` 到冷却截止后允许新请求轮换，旧码仍不可校验。
- 已被现有账号的登录名或联系邮箱占用时，仍进入相同的单行加锁、HMAC 和尝试次数状态机，但邮件只提示
  已有账号且绝不交付随机验证码；最终提交也会再次检查账号占用。公开响应、数据库访问和 SMTP 同步路径
  保持一致，缩小响应时间形成的账号枚举侧信道。

密码要求至少 12 个 Unicode 字符、UTF-8 编码不超过 72 字节、不含空白，且同时含大小写字母、数字和符号；72 字节
上限防止 BCrypt 静默忽略后续字节。服务端先以无锁短查询拒绝随机/过期票据，再在事务外使用公平、非等待
的 BCrypt CPU 舱壁（默认并发 2）；拿不到槽位立即返回 429，不让匿名请求占满 Web 线程或数据库连接。
事务内仍二次锁定并校验票据。明文只用于当前请求内 BCrypt 编码；待审核期间仅保存摘要，申请进入
`APPROVED` 或 `REJECTED` 时均清空该摘要。验证码、票据、密码及其摘要不得进入日志、实体 `toString`
或审核通知 outbox。

## 2. 申请与审核不变量

`t_kb_registration_application` 每次成功提交保存一条不可变申请事实，邮箱列不唯一，状态为
`PENDING / APPROVED / REJECTED`。同邮箱：

- 已有正式账号或 `PENDING` 申请时拒绝再次提交；
- `APPROVED` 不可重开；
- `REJECTED` 可用新的邮箱验证票据重新申请，但必须新增一行，不覆盖旧申请、审核人与原因。

`t_kb_registration_submission_claim` 先把 `client_submission_id` 与首次票据摘要绑定，串行化两个不同票据抢用
同一 UUID 的并发请求；申请表同时保存该 UUID 与票据摘要。提交事务采用 READ COMMITTED：首个请求已提交但
HTTP 响应丢失时，重试即使先看到票据已消费，也会重读并返回原 `application_id/status/created_at`，不会
再次 BCrypt、创建第二条申请或把数据库唯一键异常直接暴露给客户端。

审核列表、通过和驳回都必须**同时**满足 `user:manage` 与 `tenant:manage`。类级权限拦截负责前者，方法内
再次显式校验后者，不能把多值权限注解的“任一满足”误当成 AND。前端同样嵌套两层路由守卫，但服务端是
最终授权边界。

通过申请在一个数据库事务内完成：锁定仍为 `PENDING` 的申请 → 校验目标租户存在且启用 → 至少选择一个
角色且全部属于该租户 → 子租户角色不得携带平台级权限 → 创建 `LOCAL/ENABLED` 正式账号（完整邮箱同时
作为 `username` 和联系邮箱，沿用用户自设密码，不要求首次改密）→ 绑定角色 → CAS 推进为 `APPROVED`、
清除申请密码摘要 → 把本次授予角色写入 `t_kb_registration_application_role` 不可变快照 → 写审核通过
outbox。任一步失败全部回滚，两个管理员并发审核只能有一个成功。审核列表读取该快照，不读取账号当前
角色，因而账号后续调权不会改写历史审核事实。

驳回原因必填；锁行、CAS 推进 `REJECTED`、清除密码摘要和写驳回 outbox 同样处于一个事务。通过/驳回
均进入操作审计。账号只在通过事务中产生：`PENDING` 和 `REJECTED` 行永远不可登录。

## 3. 审核通知 outbox

验证码邮件为用户当前请求的同步结果：SMTP 不可用或发送失败时发码失败，不会向客户端谎报“已发送”。
审核结果则先与审核事实原子写入 `t_kb_mail_outbox`，事务提交后由调度器发送，避免 SMTP 短暂故障回滚
已经创建的账号或审核结论。

派发器先在短事务中用 `FOR UPDATE SKIP LOCKED` 选取到期行，并用 `next_retry_at + lock_version` 领取有界
lease；事务提交后才执行 SMTP，最后另起短事务按 lease 版本 CAS 为 `SENT/FAILED`。慢 SMTP 不持有行锁或
数据库连接，多实例不会在有效 lease 内重复领取。失败按指数退避并累计次数，到达上限后保留 `FAILED` 行
供运维排查。SMTP 已接受邮件而进程在标记 `SENT` 前崩溃时，lease 到期后可能再次发送，这是不引入分布式
SMTP 事务时明确接受的 at-least-once 边界。outbox 只承载审核结果，不承载验证码或注册票据。
邮件能力关闭且没有待投递任务时调度器保持静默；存在 backlog 时只记录一次不可用错误，恢复后记录一次
恢复事件，避免每 5 秒刷屏掩盖真正故障。

## 4. 配置与失败语义

邮件配置前缀为 `mail`，部署变量为 `ADMIN_MAIL_*`。只有 `enabled=true`，且 host、有效端口、username、
password、最终 from 和三个 1–60000ms 超时都有效时，SMTP adapter 才可用；`from` 可留空并回退到
username。465 默认启用隐式 TLS；关闭隐式 TLS 时会强制 STARTTLS，服务器不支持升级则在认证前失败，
不存在携带授权码的明文 SMTP 模式。两种模式都校验服务器身份。任何凭据不完整或非法超时都安全关闭，
不尝试连接，也不记录凭据。`ADMIN_MAIL_LOGIN_URL` 仅用于审核通过邮件；空值时邮件省略登录链接，生产应显式配置为控制台
外部 HTTPS 登录地址，不能回退到本机地址。

注册配置前缀为 `registration`，部署变量为 `REGISTRATION_*`。`REGISTRATION_ENABLED=true` 只是功能开关；
`REGISTRATION_HMAC_KEY` 默认空且少于 32 字符时，公开注册统一返回“服务暂不可用”，已有账号登录不受影响。
HMAC 密钥轮换会使当前验证码失效；生产应在安全配置中心注入稳定高熵值，不得写入镜像、仓库或日志。

默认值：验证码 10 分钟、票据 15 分钟、重发 60 秒、最多 5 次尝试；发码每邮箱每小时 6 次、每 IP
每小时 20 次、单实例全局每分钟 100 次；验证码校验每 IP 每小时 60 次、单实例全局每分钟 300 次；
最终提交每 IP 每小时 20 次、单实例全局每分钟 100 次。同步注册邮件舱壁默认并发 4（允许 1–8），BCrypt
舱壁默认并发 2（允许 1–4）；对应 `REGISTRATION_MAIL_CONCURRENCY` 与
`REGISTRATION_PASSWORD_HASH_CONCURRENCY`。outbox 单轮 50 封、最多 5 次、基础退避 60 秒、lease 60 秒、
每 5 秒调度；lease 由 `REGISTRATION_OUTBOX_LEASE_SECONDS` 配置。所有匿名限流都在服务层、数据库查询前
执行，但当前为进程内固定窗口，多实例总量边界必须由共享网关/WAF 补齐。

注册发码依赖的登录滑块还具有独立资源边界：单来源每分钟最多签发 20 个 challenge，单实例全局默认
每分钟 120 个，同时最多执行 2 个 PNG 生成任务，超限或并发舱壁已满均立即返回 429。challenge 与 proof
缓存各最多保留 10,000 条；容量已满时拒绝新状态，不驱逐仍有效的旧状态。对应部署变量为
`AUTH_CAPTCHA_GLOBAL_ISSUE_RATE_LIMIT_PER_MINUTE` 与 `AUTH_CAPTCHA_MAX_GENERATION_CONCURRENCY`，仍需由
共享网关/WAF 补齐多实例总量限制。

保留清理默认每小时第 15 分钟运行，单轮最多 50 批、每批 200 行（10,000 行/小时），严格高于单实例
最终提交全局上限 100/分钟（6,000 行/小时）。过期 `ISSUED/VERIFIED` 临时状态在额外 24 小时后物理
删除，`CONSUMED/INVALIDATED` 保留 7 天。`PENDING` 申请超过 30 天后逐行进入独立事务：再次锁行确认
仍未审核，清除密码摘要，以 `REJECTED` 存储“系统自动关闭”原因，并在同事务写通知 outbox；邮件明确
这不是人工驳回，申请人可重新验证邮箱提交。主键游标让毒行不阻塞后续候选，人工审核与自动关闭只有一方
能成功。生产不得长期关闭 `REGISTRATION_CLEANUP_ENABLED`。

终态申请与通知 outbox 本期不自动删除，部署方应按合规要求选择 90–365 天保留期并安排批量匿名化/归档；
至少监控 `t_kb_registration_application` 按状态的数量/最早 `updated_at`，以及 `t_kb_mail_outbox` 按状态的
数量/最早 `next_retry_at`。达到组织保留期限前完成归档任务；不能直接删除 `PENDING`，否则会同时丢失
防重和审核事实。

## 5. 数据库升级与登录落点

Flyway `V26__email_registration.sql` 新建以下六表，统一使用 `utf8mb4_general_ci`；这些注册前/跨租户
工作流表不进入租户行围栏：

- `t_kb_email_identity_claim`：全局邮箱身份声明；
- `t_kb_email_verification`：验证码、交付状态与一次性票据；
- `t_kb_registration_application`：每次提交的独立申请事实；
- `t_kb_registration_submission_claim`：浏览器提交 UUID 的票据绑定；
- `t_kb_registration_application_role`：审核授予角色的不可变快照；
- `t_kb_mail_outbox`：非凭据通知邮件。

身份声明表把邮箱格式 `username` 与联系 `email` 投影到同一个 `trim + lower-case` 主键，解决“两列各自唯一”仍无法阻止
`A.username = B.email` 的交叉占用。声明与账号创建/更新处于同一事务；数据库主键裁决并发竞争；账号逻辑
删除不释放声明，只有活跃账号主动换掉、且用户名不再引用的旧联系邮箱才释放。

`t_kb_admin_user`、
`t_kb_auth_token`、`t_kb_login_audit` 与 `t_kb_operation_audit` 的 `username` 从 64 扩为 254，保留原唯一键
和索引，以容纳完整邮箱登录名。迁移不收窄历史 `email VARCHAR(256)`；新写入仍按 RFC 总长 254 校验。

升级前必须在目标库运行以下只读预检；有结果时需由管理员确认真实归属并在 V26 前处理，迁移不会自动合并：

```sql
SELECT normalized_email,
       GROUP_CONCAT(DISTINCT user_id ORDER BY user_id) AS owner_user_ids
FROM (
    SELECT LOWER(TRIM(username)) AS normalized_email, user_id
    FROM t_kb_admin_user
    WHERE username LIKE '%@%'
    UNION ALL
    SELECT LOWER(TRIM(email)) AS normalized_email, user_id
    FROM t_kb_admin_user
    WHERE email IS NOT NULL
      AND TRIM(email) <> ''
      AND email LIKE '%@%'
      AND source <> 'OIDC'
      AND CHAR_LENGTH(TRIM(email)) <= 254
) identity_source
GROUP BY normalized_email
HAVING COUNT(DISTINCT user_id) > 1;
```

历史 OIDC 账号的 `email_verified` 事实没有落库，V26 不会把这类联系邮箱回填成全局身份声明，避免未经证实
的 IdP email 永久占用他人注册身份。升级后的 OIDC JIT 只有在签名 id_token 明确携带
`email_verified=true` 时才保存并声明联系邮箱；false 或缺失一律忽略。邮箱格式的既有登录名仍按登录身份
回填，其他来源的既有联系邮箱照常参与上面的冲突预检。

V25 及更早代码不会维护声明表，所以 V26 采用单版本切换：迁移窗口冻结人工建号、联系邮箱修改、SSO 首次
建号和注册审批，全部 server 实例升级成功后才解除。回退旧镜像时继续冻结这四类写入；再次升级前重跑上面
的预检并核对声明差异。新旧版本混跑或回退后继续写账号，不在兼容承诺内。

管理员批准并分配角色后，用户使用注册邮箱和自设密码登录。控制台根路径 `/`、登录成功后的默认跳转以及
未知受保护路径统一解析到企业知识首页 `/home`；首页本身只要求已认证，并按当前权限裁剪指标、快捷操作
和请求，不把低权限账号重定向到一个必然 403 的功能页。

## 6. 验收

服务端覆盖邮箱语法与大小写、滑块 proof 一次性消费、请求体上限、验证码 `ISSUING/DELIVERED` 并发竞态、
冷却/过期/错误次数、常量时间校验、票据单次消费、提交回执恢复、不同票据抢用 UUID、拒绝后新增申请、
双权限、跨租户角色拒绝、角色历史快照、并发审核、BCrypt/SMTP 舱壁、密码摘要清理、SMTP readiness/TLS/
超时与 outbox lease 重试。控制台覆盖三步注册恢复、稳定提交 UUID、审核筛选与错误重试、终态租户/角色
快照、权限预览、首页权限裁剪和 `/home` 默认落点；部署门禁覆盖 `.env.example` 重复键/个人路径、compose
展开与文档契约。
