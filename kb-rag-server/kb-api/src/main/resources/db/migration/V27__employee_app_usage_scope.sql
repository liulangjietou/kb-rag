-- 员工使用正式应用与应用管理分开授权；数据范围仍归属于角色。
ALTER TABLE t_kb_role
    ADD COLUMN app_scope_all TINYINT NOT NULL DEFAULT 0 COMMENT '是否可使用本租户全部应用，仍需 app:use 权限';

CREATE TABLE t_kb_role_app
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    role_id      VARCHAR(64) NOT NULL COMMENT '所属角色业务标识，租户通过角色校验',
    app_id       VARCHAR(64) NOT NULL COMMENT '可使用应用的业务标识',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_app (role_id, app_id),
    KEY idx_app (app_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '角色可使用的应用，替换范围时物理删除旧关联';

INSERT INTO t_kb_permission (code, name, module, module_name, sort_order)
VALUES ('app:use', '使用已发布的应用', 'APP', '应用', 5);

-- 仅明确的内置超级管理员获得初始使用权，其他角色由管理员按应用范围授权。
UPDATE t_kb_role
SET app_scope_all = 1
WHERE builtin = 1 AND code = 'SUPER_ADMIN' AND deleted = 0;

INSERT INTO t_kb_role_permission (role_id, permission_code)
SELECT role_id, 'app:use'
FROM t_kb_role r
WHERE r.builtin = 1 AND r.code = 'SUPER_ADMIN' AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM t_kb_role_permission p
                  WHERE p.role_id = r.role_id AND p.permission_code = 'app:use' AND p.deleted = 0);
