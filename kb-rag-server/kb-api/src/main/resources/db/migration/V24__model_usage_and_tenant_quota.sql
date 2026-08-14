-- M24：模型 Token 成本台账与租户月度配额。
--
-- 配额计数与明细台账分表：前者只有“租户 + 月份”一行，承担并发条件更新；后者按调用追加，
-- 用于审计与成本分析。若直接在明细表 SUM 后判断，并发请求会同时看到旧值并一起越过配额。
SET NAMES utf8mb4;

ALTER TABLE t_kb_tenant
    ADD COLUMN monthly_token_quota BIGINT NOT NULL DEFAULT 0
        COMMENT '每月模型 Token 配额，0 表示不限制' AFTER builtin;

CREATE TABLE t_kb_model_usage_monthly
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    tenant_id       VARCHAR(64) NOT NULL COMMENT '租户业务标识',
    usage_month     CHAR(7)     NOT NULL COMMENT '计费月份，格式 YYYY-MM，按 UTC+8 归属',
    used_tokens     BIGINT      NOT NULL DEFAULT 0 COMMENT '已经结算的 Token',
    reserved_tokens BIGINT      NOT NULL DEFAULT 0 COMMENT '在途调用预占的 Token',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version    INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，固定为 0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_month (tenant_id, usage_month),
    KEY idx_usage_month (usage_month)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户模型 Token 月度原子计数器';

CREATE TABLE t_kb_model_price
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    provider                 VARCHAR(64)  NOT NULL COMMENT '供应商标识',
    capability               VARCHAR(32)  NOT NULL COMMENT '能力：CHAT/EMBEDDING/RERANK/VISION/MULTIMODAL_EMBEDDING',
    model                    VARCHAR(128) NOT NULL COMMENT '模型标识',
    currency                 CHAR(3)      NOT NULL COMMENT 'ISO 4217 币种，例如 CNY/USD',
    input_price_micros       BIGINT       NOT NULL DEFAULT 0 COMMENT '每百万输入 Token 价格，单位为该币种的 10^-6',
    output_price_micros      BIGINT       NOT NULL DEFAULT 0 COMMENT '每百万输出 Token 价格，单位为该币种的 10^-6',
    enabled                  TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用；停用后新调用记为未定价',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted                  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_capability_model (provider, capability, model)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='模型价格配置，新调用在台账中快照当时价格';

CREATE TABLE t_kb_model_usage
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    usage_id                 VARCHAR(64)  NOT NULL COMMENT '调用业务标识',
    tenant_id                VARCHAR(64)  NOT NULL COMMENT '承担配额与成本的租户',
    request_id               VARCHAR(64)           DEFAULT NULL COMMENT '请求关联标识，后台任务可为空',
    source                   VARCHAR(32)  NOT NULL COMMENT '来源：CONSOLE/KNOWLEDGE_API/MEMORY_API/SCHEDULED/INTERNAL',
    source_id                VARCHAR(128)          DEFAULT NULL COMMENT '用户、Key 或任务的安全业务标识，不存凭据',
    provider                 VARCHAR(64)  NOT NULL COMMENT '供应商标识',
    capability               VARCHAR(32)  NOT NULL COMMENT '模型能力',
    model                    VARCHAR(128) NOT NULL COMMENT '模型标识',
    status                   VARCHAR(16)  NOT NULL COMMENT 'RESERVED/SUCCEEDED/FAILED',
    reserved_tokens          BIGINT       NOT NULL COMMENT '调用前按输入上界和输出预算预占的 Token',
    input_tokens             BIGINT       NOT NULL DEFAULT 0 COMMENT '供应商返回的输入 Token，未知时为估算值',
    output_tokens            BIGINT       NOT NULL DEFAULT 0 COMMENT '供应商返回的输出 Token，未知时为 0',
    total_tokens             BIGINT       NOT NULL DEFAULT 0 COMMENT '本次配额实际结算 Token',
    estimated                TINYINT      NOT NULL DEFAULT 0 COMMENT '1 表示供应商未返回用量，按预占值估算结算',
    priced                   TINYINT      NOT NULL DEFAULT 0 COMMENT '1 表示命中价格配置并完成成本估算',
    currency                 CHAR(3)               DEFAULT NULL COMMENT '本次价格快照的 ISO 4217 币种',
    input_price_micros       BIGINT                DEFAULT NULL COMMENT '每百万输入 Token 价格快照',
    output_price_micros      BIGINT                DEFAULT NULL COMMENT '每百万输出 Token 价格快照',
    cost_micros              BIGINT       NOT NULL DEFAULT 0 COMMENT '估算成本，单位为币种的 10^-6',
    error_type               VARCHAR(64)           DEFAULT NULL COMMENT '失败类型，不记录异常正文或请求内容',
    completed_at             DATETIME              DEFAULT NULL COMMENT '调用结算或失败时间',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted                  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，固定为 0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_id (usage_id),
    KEY idx_tenant_month (tenant_id, created_at),
    KEY idx_request_id (request_id),
    KEY idx_model (provider, capability, model, created_at),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='模型调用 Token 与成本明细台账';
