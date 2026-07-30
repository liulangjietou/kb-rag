-- M16 企业化：多租户 + 文档级数据权限 + 操作审计 + 组同步/SSO 的落库部分。
--
-- 三条设计取舍，写在这里免得后人重新推一遍：
--   ① tenant_id 只加在根聚合表（用户 / 角色 / 知识库 / API Key / 评测集 / 应用），从属资源
--      （文档、切片、任务、反馈……）经根资源归属租户。给四十张表全加列不叫隔离叫散弹枪：
--      子表查询永远先过父表的租户行过滤，再多一列只是第二个可以不一致的事实源。
--   ② username 保持全局唯一：登录页不问租户。会话令牌、登录审计、M11 以来的一切都以
--      username 为键，改成租户内唯一会让"当前用户"需要一个复合键，波及每一张关联表。
--   ③ 权限码目录与其字面量是全局资源：每个租户各自建角色（t_kb_role 带 tenant_id），但
--      权限码目录全租户共用 —— 码是代码里 @RequiresPermission 的字面量，按租户分裂目录
--      没有任何一行代码能消费这种差异。
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 租户表与默认租户种子
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_tenant
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    tenant_id    VARCHAR(64) NOT NULL COMMENT '控制台对外暴露的业务标识，tnt_ 前缀',
    code         VARCHAR(64) NOT NULL COMMENT '租户码，建后不可改，索引命名的租户段由它派生',
    name         VARCHAR(64) NOT NULL COMMENT '租户中文名',
    status       VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '租户状态：ENABLED 启用 / DISABLED 停用（停用即拒绝该租户全部账号登录）',
    builtin      TINYINT     NOT NULL DEFAULT 0 COMMENT '置 1 为内置租户：不可停用',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_id (tenant_id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户，根聚合表行级隔离的挂载点';

-- 默认租户 id 是字面量，理由同 V16 的内置角色：存量根聚合行靠列 DEFAULT 直接落进来，
-- 一个随机 id 会让"存量行归谁"变成一次必须跑对的 UPDATE。
INSERT INTO t_kb_tenant (tenant_id, code, name, builtin)
VALUES ('tnt_default0000000', 'DEFAULT', '默认租户', 1);

-- ---------------------------------------------------------------------------
-- 2. 根聚合表加 tenant_id（存量行由 DEFAULT 划入默认租户，升级零迁移）
-- ---------------------------------------------------------------------------

ALTER TABLE t_kb_admin_user
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER user_id,
    ADD KEY idx_tenant (tenant_id);

-- 角色 code 的唯一范围从全局收缩到租户内：建租户要为它复制五个内置角色，SUPER_ADMIN
-- 这样的 code 必然在每个租户各出现一次，全局唯一键会把复制第一步就顶回去。
ALTER TABLE t_kb_role
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER role_id,
    ADD KEY idx_tenant (tenant_id),
    DROP KEY uk_code,
    ADD UNIQUE KEY uk_tenant_code (tenant_id, code);

ALTER TABLE t_kb_knowledge_base
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER kb_id,
    ADD KEY idx_tenant (tenant_id);

ALTER TABLE t_kb_api_key
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER key_id,
    ADD KEY idx_tenant (tenant_id);

ALTER TABLE t_kb_eval_dataset
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER dataset_id,
    ADD KEY idx_tenant (tenant_id);

ALTER TABLE t_kb_app
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER app_id,
    ADD KEY idx_tenant (tenant_id);

-- 账号来源语义扩展：SSO 三协议各算一种来源，列宽 16 已够，只更新注释。
ALTER TABLE t_kb_admin_user
    MODIFY COLUMN source VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL 本地建号 / LDAP 域账号 / OIDC / SAML / CAS 单点登录建号';

-- ---------------------------------------------------------------------------
-- 3. 文档级数据权限：可见性列 + 文档 ACL 表
-- ---------------------------------------------------------------------------

ALTER TABLE t_kb_document
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT '可见性：INHERIT 继承库可见性 / RESTRICTED 仅授权角色可读内容';

CREATE TABLE t_kb_doc_acl
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    document_id  VARCHAR(64) NOT NULL COMMENT '文档业务标识，引用 t_kb_document.doc_id',
    role_id      VARCHAR(64) NOT NULL COMMENT '被授权角色业务标识，引用 t_kb_role.role_id',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    -- 逻辑删除让同一组合可能出现多行历史，因此不设唯一键：改绑一律先物理删旧行再插新行，
    -- 与 V16 三张角色关联表同一纪律。
    KEY idx_doc (document_id),
    KEY idx_role (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='受限文档与授权角色的绑定关系，visibility=RESTRICTED 时参与判定';

-- ---------------------------------------------------------------------------
-- 4. 组同步：角色授予来源
-- ---------------------------------------------------------------------------

-- 组同步每次登录全量替换 LDAP_SYNC 行，MANUAL 行永不触碰：管理员手工授的角色被夜里
-- 一次登录悄悄撤掉，是排查不出来的那类事故。
ALTER TABLE t_kb_user_role
    ADD COLUMN granted_by VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '授予来源：MANUAL 管理员手工授予 / LDAP_SYNC 目录组同步授予';

-- ---------------------------------------------------------------------------
-- 5. 开放 API 终端用户反馈：渠道维度
-- ---------------------------------------------------------------------------

ALTER TABLE t_kb_retrieval_feedback
    ADD COLUMN channel     VARCHAR(16) NOT NULL DEFAULT 'CONSOLE' COMMENT '反馈渠道：CONSOLE 控制台 / OPEN_API 开放接口',
    ADD COLUMN end_user_id VARCHAR(64)          DEFAULT NULL COMMENT '调用方自报的终端用户标识，平台不做真实性背书';

-- 洞察行记下"这次检索是谁调的"。反馈接口只凭 request_id 认账是不够的：request_id 由 X-Request-Id
-- 头带入、可被调用方自选，洞察行本身又不记调用方，于是任何持有合法 Key 的应用只要拿到别人的
-- request_id，就能对自己无权访问的知识库写反馈。补上 app_id 后，反馈接口能把洞察行的应用与
-- 当前 Key 的授权范围对上，"只接受自己检索过的那次调用"才真正成立。
-- 控制台调试检索没有应用维度，该列留空；反馈接口据此拒绝对控制台检索的反馈。
ALTER TABLE t_kb_search_insight
    ADD COLUMN app_id VARCHAR(64) DEFAULT NULL COMMENT '开放接口调用方应用标识，控制台调试检索为空',
    ADD KEY idx_request (request_id);

-- ---------------------------------------------------------------------------
-- 6. 操作审计表
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_operation_audit
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    audit_id     VARCHAR(64) NOT NULL COMMENT '审计记录业务标识',
    tenant_id    VARCHAR(64) NOT NULL COMMENT '操作者所属租户业务标识',
    user_id      VARCHAR(64) NOT NULL COMMENT '操作者用户业务标识',
    username     VARCHAR(64) NOT NULL COMMENT '操作者登录名，冗余存储使记录在账号删除后仍可读',
    module       VARCHAR(32) NOT NULL COMMENT '操作所属模块，如 KB / DOCUMENT / USER',
    action       VARCHAR(64) NOT NULL COMMENT '动作标识，如 CREATE / UPDATE / DELETE / RELEASE',
    target_type  VARCHAR(32) NOT NULL COMMENT '操作对象类型，如 KNOWLEDGE_BASE / DOCUMENT / ROLE',
    target_id    VARCHAR(64)          DEFAULT NULL COMMENT '操作对象业务标识，批量操作可为空',
    detail       JSON                 DEFAULT NULL COMMENT '业务 id 与摘要字段，绝不存请求体原文（口令与文档内容都从写端点过）',
    client_ip    VARCHAR(64)          DEFAULT NULL COMMENT '客户端 IP',
    request_id   VARCHAR(64)          DEFAULT NULL COMMENT '请求链路标识，与应用日志对账',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_id (audit_id),
    KEY idx_tenant_created (tenant_id, created_at),
    KEY idx_username (username),
    KEY idx_target (target_type, target_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='管理台写操作审计，回答"谁在什么时候改了什么"';

-- ---------------------------------------------------------------------------
-- 7. 权限码新增：租户管理
-- ---------------------------------------------------------------------------

-- 只授超级管理员：KB_ADMIN 管库不管租户，租户管理页是唯一的跨租户视角，入口越少越好。
INSERT INTO t_kb_permission (code, name, module, module_name, sort_order)
VALUES ('tenant:manage', '租户管理', 'SYSTEM', '系统', 40);

INSERT INTO t_kb_role_permission (role_id, permission_code)
VALUES ('role_superadmin000', 'tenant:manage');
