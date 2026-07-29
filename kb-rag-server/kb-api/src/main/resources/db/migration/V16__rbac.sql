-- RBAC 权限体系（D17 兑现）：把"单管理员"扩成"多用户 + 角色 + 权限码 + 知识库数据范围"。
--
-- 三条设计取舍，写在这里免得后人重新推一遍：
--   ① 不新建用户表，而是把 t_kb_admin_user 长成用户表。会话令牌表与登录审计表都以 username 为
--      外键语义，另起一张 t_kb_user 就要把那两张表一起改掉，还要在两处维护同一个人。
--   ② 权限码落库而非只在代码里写枚举：管理台的"角色-权限"勾选面板要按模块分组展示中文名，
--      这份目录就是它的数据源。代码侧仍有常量类做编译期约束，两边靠本脚本的 code 对齐。
--   ③ 数据范围挂在角色而非用户上：一个人的可见库集合等于其全部角色的并集，任一角色开了
--      kb_scope_all 即为全库可见。挂在用户上会让"给十个人同一份范围"变成十行要同步的配置。
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 账号表升级为用户表
-- ---------------------------------------------------------------------------

-- 域账号（source=LDAP）的密码由域控校验，本地不存也不比对，因此这一列必须允许为空。
ALTER TABLE t_kb_admin_user
    MODIFY COLUMN password_hash VARCHAR(128) DEFAULT NULL COMMENT 'BCrypt 摘要，接口永不回传；域账号为 null（密码由域控校验）';

ALTER TABLE t_kb_admin_user
    ADD COLUMN user_id      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '控制台对外暴露的业务标识' AFTER id,
    ADD COLUMN display_name VARCHAR(128)         DEFAULT NULL COMMENT '展示名，域账号首次登录时取登录名兜底' AFTER username,
    ADD COLUMN email        VARCHAR(256)         DEFAULT NULL COMMENT '邮箱，仅作展示与联系用途' AFTER display_name,
    ADD COLUMN source       VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL 本地建号 / LDAP 域账号单点登录' AFTER email,
    ADD COLUMN status       VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED 启用 / DISABLED 停用（停用即拒绝登录并撤销会话）' AFTER source;

-- 存量行（首启生成的 admin）补业务标识：UUID() 在 UPDATE 里逐行求值，所以不会撞唯一键。
UPDATE t_kb_admin_user
SET user_id = CONCAT('usr_', SUBSTRING(REPLACE(UUID(), '-', ''), 1, 16))
WHERE user_id = '';

ALTER TABLE t_kb_admin_user
    ADD UNIQUE KEY uk_user_id (user_id);

ALTER TABLE t_kb_admin_user COMMENT ='控制台用户账号，本地建号与域账号单点登录共用一张表';

-- ---------------------------------------------------------------------------
-- 2. 权限码目录
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_permission
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    code         VARCHAR(64)  NOT NULL COMMENT '权限码，形如 module:action，与代码里的 PermissionCodes 常量一一对应',
    name         VARCHAR(128) NOT NULL COMMENT '权限中文名，管理台勾选面板直接展示',
    module       VARCHAR(32)  NOT NULL COMMENT '所属模块分组键，管理台按此聚合展示',
    module_name  VARCHAR(64)  NOT NULL COMMENT '模块中文名',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '同模块内的展示顺序',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_module (module, sort_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='权限码目录，管理台角色配置面板的数据源';

-- ---------------------------------------------------------------------------
-- 3. 角色与三张关联表
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_role
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    role_id      VARCHAR(64)  NOT NULL COMMENT '控制台对外暴露的业务标识',
    code         VARCHAR(64)  NOT NULL COMMENT '角色码，内置角色为固定值，自定义角色由运营人员填写',
    name         VARCHAR(128) NOT NULL COMMENT '角色中文名',
    description  VARCHAR(512)          DEFAULT NULL COMMENT '角色用途说明',
    builtin      TINYINT      NOT NULL DEFAULT 0 COMMENT '置 1 为内置角色：不可删除、角色码不可改',
    kb_scope_all TINYINT      NOT NULL DEFAULT 0 COMMENT '置 1 表示该角色可见全部知识库，此时 t_kb_role_kb 的明细被忽略',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_id (role_id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色，权限码与知识库数据范围的挂载点';

CREATE TABLE t_kb_role_permission
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    role_id         VARCHAR(64) NOT NULL COMMENT '角色业务标识',
    permission_code VARCHAR(64) NOT NULL COMMENT '权限码，引用 t_kb_permission.code',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version    INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    -- 逻辑删除让同一组合可能出现多行历史，因此不设唯一键：改绑权限一律先物理删旧行再插新行。
    KEY idx_role (role_id),
    KEY idx_permission (permission_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色与权限码的绑定关系';

CREATE TABLE t_kb_user_role
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id      VARCHAR(64) NOT NULL COMMENT '用户业务标识，引用 t_kb_admin_user.user_id',
    role_id      VARCHAR(64) NOT NULL COMMENT '角色业务标识，引用 t_kb_role.role_id',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_role (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户与角色的绑定关系';

CREATE TABLE t_kb_role_kb
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    role_id      VARCHAR(64) NOT NULL COMMENT '角色业务标识',
    kb_id        VARCHAR(64) NOT NULL COMMENT '该角色可见的知识库业务标识',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    KEY idx_role (role_id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色的知识库数据范围明细，kb_scope_all=1 时不参与判定';

-- ---------------------------------------------------------------------------
-- 4. 权限码目录初始化
-- ---------------------------------------------------------------------------

INSERT INTO t_kb_permission (code, name, module, module_name, sort_order)
VALUES ('kb:read', '查看知识库与文档', 'KB', '知识库', 10),
       ('kb:write', '管理知识库与索引配置', 'KB', '知识库', 20),
       ('kb:delete', '删除知识库', 'KB', '知识库', 30),
       ('doc:write', '管理文档、分片与数据源', 'KB', '知识库', 40),
       ('doc:review', '内容审核与回收站', 'KB', '知识库', 50),
       ('search:debug', '检索与问答调试', 'RETRIEVAL', '检索', 10),
       ('feedback:manage', '处理检索反馈', 'RETRIEVAL', '检索', 20),
       ('eval:read', '查看评测集与评测结果', 'EVAL', '评测', 10),
       ('eval:write', '维护评测集与人工标注', 'EVAL', '评测', 20),
       ('eval:run', '发起评测运行', 'EVAL', '评测', 30),
       ('app:read', '查看应用与版本', 'APP', '应用', 10),
       ('app:write', '管理应用与版本配置', 'APP', '应用', 20),
       ('app:release', '提测、发布与回滚', 'APP', '应用', 30),
       ('apikey:manage', '管理对外 API Key', 'OPENAPI', '开放接口', 10),
       ('audit:read', '查看调用审计与检索洞察', 'OPENAPI', '开放接口', 20),
       ('system:config', '系统设置、告警与词典', 'SYSTEM', '系统', 10),
       ('user:manage', '用户管理', 'SYSTEM', '系统', 20),
       ('role:manage', '角色与权限管理', 'SYSTEM', '系统', 30);

-- ---------------------------------------------------------------------------
-- 5. 内置角色初始化
-- ---------------------------------------------------------------------------

-- 五个内置角色一律 kb_scope_all=1：它们表达的是"能做什么"，"能看哪些库"由运营人员
-- 按需新建自定义角色来收窄，这样内置角色的语义不随某个部署的库划分而漂移。
INSERT INTO t_kb_role (role_id, code, name, description, builtin, kb_scope_all)
VALUES ('role_superadmin000', 'SUPER_ADMIN', '超级管理员', '拥有全部权限与全部知识库范围，不可删除', 1, 1),
       ('role_kbadmin00000', 'KB_ADMIN', '知识库管理员', '知识库、文档、评测与应用的完整管理权，不含用户与角色管理', 1, 1),
       ('role_editor000000', 'EDITOR', '内容编辑', '上传与维护文档，可做检索调试，不能删库也不能审核', 1, 1),
       ('role_reviewer0000', 'REVIEWER', '内容审核员', '审核文档上下架、处理检索反馈与回收站', 1, 1),
       ('role_viewer000000', 'VIEWER', '只读用户', '只读浏览与检索调试，域账号首次登录的默认角色', 1, 1);

INSERT INTO t_kb_role_permission (role_id, permission_code)
SELECT 'role_superadmin000', code
FROM t_kb_permission;

INSERT INTO t_kb_role_permission (role_id, permission_code)
SELECT 'role_kbadmin00000', code
FROM t_kb_permission
WHERE code NOT IN ('user:manage', 'role:manage');

INSERT INTO t_kb_role_permission (role_id, permission_code)
VALUES ('role_editor000000', 'kb:read'),
       ('role_editor000000', 'doc:write'),
       ('role_editor000000', 'search:debug'),
       ('role_editor000000', 'eval:read'),
       ('role_editor000000', 'app:read'),
       ('role_reviewer0000', 'kb:read'),
       ('role_reviewer0000', 'doc:review'),
       ('role_reviewer0000', 'search:debug'),
       ('role_reviewer0000', 'feedback:manage'),
       ('role_reviewer0000', 'eval:read'),
       ('role_viewer000000', 'kb:read'),
       ('role_viewer000000', 'search:debug'),
       ('role_viewer000000', 'eval:read'),
       ('role_viewer000000', 'app:read');

-- ---------------------------------------------------------------------------
-- 6. 存量账号提权
-- ---------------------------------------------------------------------------

-- 迁移前库里的账号只有首启生成的那个管理员。不给它超级管理员就等于把升级后的控制台
-- 锁死：没有任何人能进用户管理页去授权。
INSERT INTO t_kb_user_role (user_id, role_id)
SELECT user_id, 'role_superadmin000'
FROM t_kb_admin_user
WHERE deleted = 0;
