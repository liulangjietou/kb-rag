-- 里程碑 M4c 的结构增量：应用、带评测门禁的版本发布、API Key，以及对外调用的审计流水。
-- 基线与 V1-V5 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
SET NAMES utf8mb4;

CREATE TABLE t_kb_app
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    app_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    name         VARCHAR(128) NOT NULL COMMENT '展示名称',
    description  VARCHAR(1024)         DEFAULT NULL COMMENT '自由文本描述',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_id (app_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='知识库应用';

CREATE TABLE t_kb_app_version
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    app_version_id  VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    app_id          VARCHAR(64) NOT NULL COMMENT '所属应用',
    version         VARCHAR(16) NOT NULL COMMENT '展示版本号，依次为 V1.0、V2.0 ……',
    status          VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        COMMENT '版本状态：DRAFT/TESTING/GATING/GATE_PASSED/GATE_LOG_ONLY/GATE_BLOCKED/RELEASED/SUPERSEDED',
    -- 发布时冻结，此后再也不会回到知识库重新读取，对应需求文档 4.7 节「配置快照」：
    -- 单个 kb_id（多知识库在 M5 才引入）、检索参数，以及问答提示词块。
    config          JSON        NOT NULL COMMENT '完整的检索与提示词配置快照',
    gate_dataset_id VARCHAR(64)          DEFAULT NULL COMMENT '门禁基线评测数据集，为 null 时跳过门禁',
    gate_run_ids    JSON                 DEFAULT NULL COMMENT '双跑的两个运行 id，候选版本在前',
    gate_verdict    VARCHAR(16)          DEFAULT NULL COMMENT '门禁三态结论：PASS 通过 / BLOCKED 拦截 / LOG_ONLY 仅记录',
    gate_reason     VARCHAR(32)          DEFAULT NULL COMMENT 'gate_verdict 背后归类后的原因',
    gate_report     JSON                 DEFAULT NULL COMMENT '双方交集指标与容差阈值',
    force_released  TINYINT     NOT NULL DEFAULT 0 COMMENT '管理员强制发布时置 1',
    force_operator  VARCHAR(64)          DEFAULT NULL COMMENT '强制发布的操作人，用于审计',
    changelog       VARCHAR(1024)        DEFAULT NULL COMMENT '版本说明',
    released_at     DATETIME             DEFAULT NULL COMMENT '本版本最近一次成为发布版的时间',
    -- 「一个应用最多一个发布版」这条约束靠虚拟列加唯一索引在数据库层强制，而不是放在应用代码里，
    -- 对应需求文档 4.7 节。其余状态和逻辑删除行上它都求值为 NULL，而 MySQL 的唯一索引允许任意多个
    -- NULL，因此只有处于发布状态的行才会去争这个槽位。
    released_slot   VARCHAR(64) GENERATED ALWAYS AS
        (IF(status = 'RELEASED' AND deleted = 0, app_id, NULL)) VIRTUAL COMMENT '发布槽位虚拟列，仅发布版求值为 app_id，其余为 NULL',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version    INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_version_id (app_version_id),
    UNIQUE KEY uk_released_slot (released_slot),
    KEY idx_app_id (app_id),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='应用版本：配置快照与发布状态机';

CREATE TABLE t_kb_api_key
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    key_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    name         VARCHAR(128) NOT NULL COMMENT '该 Key 所签发给的调用方展示名称',
    -- 只存摘要：明文仅在创建时展示一次、此后再也不会出现，因此即使数据库被拖库，
    -- 也无法拿去重放开放接口，对应需求文档 4.8 节。
    key_hash     CHAR(64)     NOT NULL COMMENT '明文 Key 的 SHA-256 摘要',
    prefix       VARCHAR(32)  NOT NULL COMMENT '仅用于展示的形式：前缀段加末尾 4 位',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED 启用 / DISABLED 停用',
    qps_limit    INT          NOT NULL DEFAULT 10 COMMENT '该 Key 的令牌桶速率',
    app_scope    JSON                  DEFAULT NULL COMMENT '授权的应用 id 列表，为 null 时授权全部应用',
    last_used_at DATETIME              DEFAULT NULL COMMENT '最近一次认证成功的时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_id (key_id),
    UNIQUE KEY uk_key_hash (key_hash),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='开放接口的 API Key：摘要存储、授权范围与配额';

CREATE TABLE t_kb_api_audit_log
(
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    audit_log_id   VARCHAR(64) NOT NULL COMMENT '控制台查询接口对外暴露的业务标识',
    key_id         VARCHAR(64) NOT NULL COMMENT '发起调用的 API Key 标识，绝不记录明文 Key',
    app_id         VARCHAR(64)          DEFAULT NULL COMMENT '被调用的应用',
    app_version_id VARCHAR(64)          DEFAULT NULL COMMENT '实际服务的应用版本，请求被拒绝时为 null',
    target_stage   VARCHAR(16)          DEFAULT NULL COMMENT '被调用的版本阶段：RELEASE 正式 / BETA 灰度',
    endpoint       VARCHAR(32)  NOT NULL COMMENT '被调用的端点：search/chat',
    -- 先按 4.2 节规则脱敏再截断：审计流水既要保持可读，又不能变成知识库已经脱敏过的那份个人数据的副本。
    query_digest   VARCHAR(200)         DEFAULT NULL COMMENT '脱敏并截断后的查询文本',
    hit_doc_ids    JSON                 DEFAULT NULL COMMENT '返回结果所属的文档 id 列表',
    latency_ms     INT         NOT NULL DEFAULT 0 COMMENT '服务端耗时，毫秒',
    degraded       JSON                 DEFAULT NULL COMMENT '本次调用的降级标记',
    override_keys  JSON                 DEFAULT NULL COMMENT '本次生效的请求级覆盖参数，对应需求文档第 5 节',
    error_code     VARCHAR(32)          DEFAULT NULL COMMENT '调用被拒绝时的业务错误码',
    request_id     VARCHAR(64)          DEFAULT NULL COMMENT '本次调用的链路追踪 id',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version   INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_log_id (audit_log_id),
    KEY idx_key_id (key_id),
    KEY idx_created_at (created_at),
    KEY idx_key_created (key_id, created_at),
    KEY idx_version_stage (app_version_id, target_stage)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='对外接口调用审计，超过保留期后归档到对象存储';

-- 给 M4b 的表补两个增量列：发布门禁要在两次运行的有效用例交集上重算 Recall@K，
-- 而这需要评判过程本来就算出、但之前被丢弃的逐用例证据计数。
-- 若改为从 overlap_ratios 反推，等于拿「每条证据的最佳覆盖率」去比对「聚合覆盖判定」，
-- 会与该次运行自己的指标悄悄对不上。
ALTER TABLE t_kb_eval_result
    ADD COLUMN evidence_hit_count   INT NOT NULL DEFAULT 0
        COMMENT 'Top K 内被覆盖的证据数，门禁交集重算的输入',
    ADD COLUMN evidence_total_count INT NOT NULL DEFAULT 0
        COMMENT '该用例声明的证据总数，门禁交集重算的输入';
