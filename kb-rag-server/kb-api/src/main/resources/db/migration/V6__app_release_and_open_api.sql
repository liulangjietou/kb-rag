-- Milestone M4c schema increment: applications, version release with the evaluation gate, API keys
-- and the outbound call audit trail.
-- The baseline and V1-V5 are never edited after release, so every later change arrives as its own migration.

CREATE TABLE t_kb_app
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    app_id       VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    name         VARCHAR(128) NOT NULL COMMENT 'display name',
    description  VARCHAR(1024)         DEFAULT NULL COMMENT 'free text description',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_id (app_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='knowledge base application';

CREATE TABLE t_kb_app_version
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    app_version_id  VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    app_id          VARCHAR(64) NOT NULL COMMENT 'owning application',
    version         VARCHAR(16) NOT NULL COMMENT 'display version, V1.0 then V2.0 and so on',
    status          VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/TESTING/GATING/GATE_PASSED/GATE_LOG_ONLY/GATE_BLOCKED/RELEASED/SUPERSEDED',
    -- Frozen at release time and never re-read from the knowledge base afterwards, requirement
    -- section 4.7 "configuration snapshot": single kb_id (multi base arrives with M5), the retrieval
    -- parameters and the question answering prompt block.
    config          JSON        NOT NULL COMMENT 'full retrieval and prompt configuration snapshot',
    gate_dataset_id VARCHAR(64)          DEFAULT NULL COMMENT 'baseline evaluation data set, null skips the gate',
    gate_run_ids    JSON                 DEFAULT NULL COMMENT 'the two run ids of the dual run, candidate first',
    gate_verdict    VARCHAR(16)          DEFAULT NULL COMMENT 'PASS/BLOCKED/LOG_ONLY, three state gate outcome',
    gate_reason     VARCHAR(32)          DEFAULT NULL COMMENT 'classified reason behind gate_verdict',
    gate_report     JSON                 DEFAULT NULL COMMENT 'intersection metrics of both sides plus the tolerance',
    force_released  TINYINT     NOT NULL DEFAULT 0 COMMENT '1 when an administrator forced the release',
    force_operator  VARCHAR(64)          DEFAULT NULL COMMENT 'who forced the release, audit trail',
    changelog       VARCHAR(1024)        DEFAULT NULL COMMENT 'version description',
    released_at     DATETIME             DEFAULT NULL COMMENT 'last time this version became the released one',
    -- Virtual column plus unique index is what enforces "at most one released version per application"
    -- in the database rather than in application code, requirement section 4.7. It resolves to NULL for
    -- every other status and for soft deleted rows, and MySQL allows any number of NULLs in a unique
    -- index, so only the released rows compete for the slot.
    released_slot   VARCHAR(64) GENERATED ALWAYS AS
        (IF(status = 'RELEASED' AND deleted = 0, app_id, NULL)) VIRTUAL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version    INT         NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_version_id (app_version_id),
    UNIQUE KEY uk_released_slot (released_slot),
    KEY idx_app_id (app_id),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='application version: configuration snapshot and release state machine';

CREATE TABLE t_kb_api_key
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    key_id       VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    name         VARCHAR(128) NOT NULL COMMENT 'display name of the caller this key was issued to',
    -- Only the digest is stored: the plaintext is shown once at creation and never again, so a database
    -- dump cannot be replayed against the open API, requirement section 4.8.
    key_hash     CHAR(64)     NOT NULL COMMENT 'SHA-256 of the plaintext key',
    prefix       VARCHAR(32)  NOT NULL COMMENT 'display only form, leading segment plus the last 4 characters',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    qps_limit    INT          NOT NULL DEFAULT 10 COMMENT 'token bucket rate of this key',
    app_scope    JSON                  DEFAULT NULL COMMENT 'allowed app ids, null authorises every application',
    last_used_at DATETIME              DEFAULT NULL COMMENT 'last successful authentication',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_id (key_id),
    UNIQUE KEY uk_key_hash (key_hash),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'API key of the open API: hash storage, scope and quota';

CREATE TABLE t_kb_api_audit_log
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    audit_log_id   VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the console query API',
    key_id         VARCHAR(64) NOT NULL COMMENT 'calling API key, never the plaintext key',
    app_id         VARCHAR(64)          DEFAULT NULL COMMENT 'application called',
    app_version_id VARCHAR(64)          DEFAULT NULL COMMENT 'application version served, null when rejected',
    target_stage   VARCHAR(16)          DEFAULT NULL COMMENT 'RELEASE/BETA, the version stage that was called',
    endpoint       VARCHAR(32)  NOT NULL COMMENT 'search/chat',
    -- Masked by the section 4.2 rules and then truncated: an audit trail must stay readable without
    -- becoming a second copy of the personal data the knowledge base already masks.
    query_digest   VARCHAR(200)         DEFAULT NULL COMMENT 'masked and truncated query',
    hit_doc_ids    JSON                 DEFAULT NULL COMMENT 'document ids of the returned nodes',
    latency_ms     INT         NOT NULL DEFAULT 0 COMMENT 'server side duration',
    degraded       JSON                 DEFAULT NULL COMMENT 'degradation markers of this call',
    override_keys  JSON                 DEFAULT NULL COMMENT 'request level overrides applied, requirement section 5',
    error_code     VARCHAR(32)          DEFAULT NULL COMMENT 'business error code when the call was rejected',
    request_id     VARCHAR(64)          DEFAULT NULL COMMENT 'correlation id of the call',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version   INT         NOT NULL DEFAULT 0,
    deleted        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_log_id (audit_log_id),
    KEY idx_key_id (key_id),
    KEY idx_created_at (created_at),
    KEY idx_key_created (key_id, created_at),
    KEY idx_version_stage (app_version_id, target_stage)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'outbound API call audit, archived to object storage after the retention window';

-- Additive columns on the M4b table: the release gate recomputes Recall@K on the intersection of the
-- effective cases of both runs, which needs the per case evidence counts the judgment already produced
-- and previously discarded. Deriving them back from overlap_ratios would compare a per evidence best
-- ratio against an aggregate coverage decision and silently disagree with the run's own metrics.
ALTER TABLE t_kb_eval_result
    ADD COLUMN evidence_hit_count   INT NOT NULL DEFAULT 0
        COMMENT 'evidences covered within the top K, gate intersection recomputation input',
    ADD COLUMN evidence_total_count INT NOT NULL DEFAULT 0
        COMMENT 'evidences the case declares, gate intersection recomputation input';
