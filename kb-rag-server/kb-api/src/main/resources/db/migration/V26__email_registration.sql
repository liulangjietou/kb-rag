-- M26：邮箱验证码注册、注册审核与可靠通知邮件。
--
-- 待审核申请与登录账号分表：只有管理员在同一事务中分配租户和角色后才创建账号，
-- 因而 PENDING/REJECTED 状态从结构上就不可能获得控制台令牌。
-- 验证码和注册票据只保存摘要；短期验证码不得写入可靠邮件发件箱。
SET NAMES utf8mb4;

-- 所有业务预条件必须在首个永久 DDL 前验证。临时表随 Flyway 会话销毁；若历史邮箱
-- 已跨用户冲突，唯一键在这里立即失败，不会留下“永久 DDL 已执行、版本却未登记”的半迁移。
CREATE TEMPORARY TABLE tmp_m26_email_identity_claim
(
    normalized_email VARCHAR(254) NOT NULL,
    owner_user_id     VARCHAR(64)  NOT NULL,
    PRIMARY KEY (normalized_email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

INSERT INTO tmp_m26_email_identity_claim (normalized_email, owner_user_id)
SELECT identity_value, user_id
FROM (
    SELECT LOWER(TRIM(username)) AS identity_value, user_id
    FROM t_kb_admin_user
    WHERE username LIKE '%@%'
    UNION
    SELECT LOWER(TRIM(email)) AS identity_value, user_id
    FROM t_kb_admin_user
    WHERE email IS NOT NULL
      AND TRIM(email) <> ''
      AND email LIKE '%@%'
      AND source <> 'OIDC'
      AND CHAR_LENGTH(TRIM(email)) <= 254
) historical_identity;

-- ---------------------------------------------------------------------------
-- 1. 登录名链路容纳 RFC 邮箱长度
-- ---------------------------------------------------------------------------

ALTER TABLE t_kb_admin_user
    MODIFY COLUMN username VARCHAR(254) NOT NULL COMMENT '全局唯一登录名；本地注册账号使用完整邮箱';

ALTER TABLE t_kb_auth_token
    MODIFY COLUMN username VARCHAR(254) NOT NULL COMMENT '会话所属的控制台账号';

ALTER TABLE t_kb_login_audit
    MODIFY COLUMN username VARCHAR(254) NOT NULL COMMENT '提交的登录名，账号不存在时也照样记录';

-- 操作审计冗余登录名；不一起扩容会让长邮箱账号在首次管理操作时写审计失败。
ALTER TABLE t_kb_operation_audit
    MODIFY COLUMN username VARCHAR(254) NOT NULL COMMENT '操作者登录名，冗余存储使记录在账号删除后仍可读';

-- ---------------------------------------------------------------------------
-- 2. 邮箱身份声明
-- ---------------------------------------------------------------------------

-- username 与 email 各自建唯一索引无法阻止跨列竞争，例如 A.username = B.email。
-- 独立声明表把两列映射到同一个规范化命名空间；历史跨用户冲突会让下面回填触发唯一键
-- 失败，必须由管理员按 M26-CONTRACTS / UPGRADING 的预检结果显式处理，迁移不静默合并。
CREATE TABLE t_kb_email_identity_claim
(
    normalized_email VARCHAR(254) NOT NULL COMMENT 'trim + lower-case 后的邮箱身份',
    owner_user_id     VARCHAR(64)  NOT NULL COMMENT '持有该身份的用户业务标识；逻辑删除不释放',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次声明时间',
    PRIMARY KEY (normalized_email),
    KEY idx_owner_user (owner_user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户名邮箱与联系邮箱共用的全局身份声明';

INSERT INTO t_kb_email_identity_claim (normalized_email, owner_user_id)
SELECT normalized_email, owner_user_id
FROM tmp_m26_email_identity_claim;

DROP TEMPORARY TABLE tmp_m26_email_identity_claim;

-- ---------------------------------------------------------------------------
-- 3. 邮箱验证码与一次性注册票据
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_email_verification
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    verification_id     VARCHAR(64)  NOT NULL COMMENT '验证流程业务标识',
    email               VARCHAR(254) NOT NULL COMMENT '标准化邮箱，大小写不敏感且全局唯一',
    code_hmac           CHAR(64)              DEFAULT NULL COMMENT '验证码 HMAC-SHA256，验证结束后清除',
    code_delivery_status VARCHAR(16) NOT NULL COMMENT 'ISSUING/DELIVERED/NONE；只有已交付验证码可校验',
    status              VARCHAR(16)  NOT NULL COMMENT 'ISSUED/VERIFIED/CONSUMED/INVALIDATED',
    attempts_remaining  INT          NOT NULL COMMENT '剩余验证码校验次数',
    expires_at          DATETIME     NOT NULL COMMENT '验证码绝对失效时间',
    resend_available_at DATETIME     NOT NULL COMMENT '允许再次发送的最早时间',
    ticket_hash         CHAR(64)              DEFAULT NULL COMMENT '一次性注册票据 SHA-256 摘要',
    ticket_expires_at   DATETIME              DEFAULT NULL COMMENT '注册票据绝对失效时间',
    verified_at         DATETIME              DEFAULT NULL COMMENT '验证码校验通过时间',
    consumed_at         DATETIME              DEFAULT NULL COMMENT '注册票据消费时间',
    request_ip_hash     CHAR(64)     NOT NULL COMMENT '来源 IP 的带密钥哈希，绝不存 IP 明文',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_id (verification_id),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_ticket_hash (ticket_hash),
    KEY idx_status_expires (status, expires_at),
    KEY idx_request_ip_updated (request_ip_hash, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='邮箱注册验证码与一次性票据状态';

-- ---------------------------------------------------------------------------
-- 4. 注册申请
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_registration_application
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    application_id      VARCHAR(64)   NOT NULL COMMENT '注册申请业务标识',
    email               VARCHAR(254)  NOT NULL COMMENT '已验证的标准化登录邮箱',
    submission_id       CHAR(36)      NOT NULL COMMENT '客户端一次提交幂等标识',
    submission_ticket_hash CHAR(64)   NOT NULL COMMENT '绑定幂等重试的高熵票据 SHA-256',
    display_name        VARCHAR(128)  NOT NULL COMMENT '申请人展示名',
    team_name           VARCHAR(128)           DEFAULT NULL COMMENT '申请人所属团队',
    password_hash       VARCHAR(128)           DEFAULT NULL COMMENT 'BCrypt 摘要，审核完成后清除',
    application_note    VARCHAR(1000)          DEFAULT NULL COMMENT '用途说明',
    status              VARCHAR(16)   NOT NULL COMMENT 'PENDING/APPROVED/REJECTED',
    email_verified_at   DATETIME      NOT NULL COMMENT '邮箱验证码校验通过时间',
    reviewed_by         VARCHAR(64)            DEFAULT NULL COMMENT '审核人用户业务标识',
    reviewed_at         DATETIME               DEFAULT NULL COMMENT '审核完成时间',
    review_reason       VARCHAR(1000)          DEFAULT NULL COMMENT '拒绝原因或审核备注',
    approved_tenant_id  VARCHAR(64)            DEFAULT NULL COMMENT '审核通过时分配的租户业务标识',
    approved_user_id    VARCHAR(64)            DEFAULT NULL COMMENT '审核通过后创建的账号业务标识',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_application_id (application_id),
    UNIQUE KEY uk_submission_id (submission_id),
    KEY idx_email_created (email, created_at),
    KEY idx_status_created (status, created_at),
    KEY idx_reviewed_by (reviewed_by),
    KEY idx_approved_tenant (approved_tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='邮箱注册申请，审核通过前不创建登录账号';

-- 并发请求先声明浏览器幂等标识；相同标识不能被另一张票据/邮箱复用。
CREATE TABLE t_kb_registration_submission_claim
(
    submission_id CHAR(36) NOT NULL COMMENT '客户端一次提交幂等标识',
    ticket_hash   CHAR(64) NOT NULL COMMENT '首次绑定的高熵票据 SHA-256',
    created_at    DATETIME NOT NULL COMMENT '首次声明时间',
    PRIMARY KEY (submission_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='注册提交幂等标识声明';

-- 审核通过时实际授予角色的不可变事实；账号后续调权不能改写历史申请。
CREATE TABLE t_kb_registration_application_role
(
    application_id VARCHAR(64) NOT NULL COMMENT '注册申请业务标识',
    role_id        VARCHAR(64) NOT NULL COMMENT '审核当时授予的角色业务标识',
    created_at     DATETIME    NOT NULL COMMENT '审核完成时间',
    PRIMARY KEY (application_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='注册审核授予角色的不可变快照';

-- ---------------------------------------------------------------------------
-- 5. 审核结果等非凭据通知的可靠发件箱
-- ---------------------------------------------------------------------------

CREATE TABLE t_kb_mail_outbox
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    outbox_id     VARCHAR(64)  NOT NULL COMMENT '发件任务业务标识',
    recipient     VARCHAR(254) NOT NULL COMMENT '收件邮箱',
    subject       VARCHAR(256) NOT NULL COMMENT '邮件主题',
    body          TEXT         NOT NULL COMMENT '纯文本正文，禁止写入验证码、票据或密码',
    status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/FAILED',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '已经失败的发送次数',
    next_retry_at DATETIME              DEFAULT NULL COMMENT '下一次允许重试时间，为空表示不再自动重试',
    last_error    VARCHAR(1000)         DEFAULT NULL COMMENT '已截断、已脱敏的最近一次错误摘要',
    sent_at       DATETIME              DEFAULT NULL COMMENT 'SMTP 服务接受邮件的时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version  INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_id (outbox_id),
    KEY idx_status_retry (status, next_retry_at, retry_count),
    KEY idx_recipient_created (recipient, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='非凭据通知邮件可靠发件箱';
