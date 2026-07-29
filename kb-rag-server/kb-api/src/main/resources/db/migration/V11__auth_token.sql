-- 控制台会话令牌从进程内存迁到数据库，这样单实例重启不再让所有会话失效。
-- 24 小时 TTL 的语义保持不变。
--
-- 与 t_kb_api_key 一致，只存令牌的 SHA-256 摘要：明文只存在于浏览器里，
-- 因此即使数据库被拖库，也无法拿去重放控制台。
SET NAMES utf8mb4;

CREATE TABLE t_kb_auth_token
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    token_hash   VARCHAR(64)  NOT NULL COMMENT '不透明 bearer 令牌的 SHA-256 摘要，也是唯一的存储形式',
    username     VARCHAR(64)  NOT NULL COMMENT '会话所属的控制台账号',
    expires_at   DATETIME     NOT NULL COMMENT '绝对过期时刻，等于签发时间加上配置的 TTL',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    -- 改密之后 revokeAll 会扫描单个账号的全部会话，
    -- 登录时的过期清理会扫描已过期的行。
    KEY idx_username (username),
    KEY idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='控制台会话令牌，可跨进程重启存活';
