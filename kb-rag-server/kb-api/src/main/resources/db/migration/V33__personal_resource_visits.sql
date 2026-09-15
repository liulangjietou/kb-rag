CREATE TABLE t_kb_resource_visit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    visited_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lock_version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_visit_owner (tenant_id, user_id, resource_type, resource_id),
    KEY idx_resource_visit_recent (tenant_id, user_id, visited_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '本人最近资源访问，不保存正文与页面参数';
