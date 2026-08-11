-- M18 补课：站点凭据接入 M16 的多租户行级隔离。V19 建表时漏了这一层，缺陷有两面，
-- 后一面比前一面严重得多：
--   管理面 —— 任何租户持 system:config 的账号都能列出、改写、删除、停用其他租户为某 host
--              配的登录凭据（secret 不回传，但改删停用是实打实的破坏面）；
--   抓取面 —— 凭据按 host 全局唯一、抓取按 host 查找，于是 B 租户只要给自己的 WebSource 登记
--              一个同 host 的 URL，夜里的同步就会把 A 租户的密码发到那个请求上，全程不需要任何
--              额外权限，也不留下"越权"的痕迹。
--
-- 三条取舍，写在这里免得后人重新推一遍：
--   ① uk_host 收缩为 uk_tenant_host(tenant_id, host)。凭据不再全局唯一：两个租户各自为
--      wiki.example.com 配一个自己的只读账号是正常业务，全局唯一键会把第二个租户建凭据的
--      动作直接顶回去 —— 与 V17 把 t_kb_role 的 uk_code 收缩成 uk_tenant_code 同一个道理。
--   ② 不额外建 idx_tenant。uk_tenant_host 的最左前缀就是 tenant_id，列表页的
--      "本租户全部凭据"和抓取侧的 "本租户 + 该 host" 都走它；再挂一个单列索引只是多一份
--      写入维护成本（V17 的几张根表两个都建了，那是冗余，不必照抄）。
--   ③ 存量行由列 DEFAULT 划入默认租户，升级零迁移、单租户部署行为完全不变。
--
-- 列 DEFAULT 只服务存量行。新建凭据时服务层从不写这一列（WebCredentialService#create），
-- tenant_id 由 MybatisPlusConfig 的 TenantLineInnerInterceptor 往 INSERT 注入，与 V17 / V21
-- 的根聚合表同一机制，前提同样是 MyBatis-Plus 保持默认的 NOT_NULL 字段策略。
--
-- 【最要紧的一条】把本表加进 KbTenantLineHandler.FENCED_TABLES 只解决了管理面。抓取面在
-- @Scheduled 的同步线程上，那条线程没有控制台主体，ignoreTable 一律返回 true、围栏整条跳过，
-- 因此 WebCredentialService#resolveFor 必须显式带租户查询（租户由 WebSource.kb_id 反查
-- t_kb_knowledge_base.tenant_id 得到）。只加列不改那个签名，抓取会继续按 host 命中任意租户
-- 的凭据 —— 那比现在更危险：现状至少是"共享一份全局凭据"这个可见的错，加列之后会变成一个
-- 看起来已经隔离、实际仍在串号的静默错误。
SET NAMES utf8mb4;

ALTER TABLE t_kb_web_credential
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER credential_id,
    DROP KEY uk_host,
    ADD UNIQUE KEY uk_tenant_host (tenant_id, host);
