-- Milestone M4b schema increment: the evaluation subsystem.
-- The baseline and V1-V4 are never edited after release, so every later change arrives as its own migration.

CREATE TABLE t_kb_eval_dataset
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    dataset_id       VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    kb_id            VARCHAR(64)  NOT NULL COMMENT 'owning knowledge base',
    name             VARCHAR(128) NOT NULL COMMENT 'display name',
    description      VARCHAR(1024)         DEFAULT NULL COMMENT 'free text description',
    -- Bumped on every case insert, edit, delete and status change; a run snapshots this value so the
    -- compare endpoint can refuse to place two runs side by side once the case set moved between them.
    dataset_revision INT          NOT NULL DEFAULT 0 COMMENT 'incremented on any case mutation',
    case_count       INT          NOT NULL DEFAULT 0 COMMENT 'derived redundant count of non deprecated cases',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version     INT          NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dataset_id (dataset_id),
    KEY idx_kb_id (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='evaluation data set';

CREATE TABLE t_kb_eval_case
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    dataset_id       VARCHAR(64) NOT NULL COMMENT 'owning data set',
    query            TEXT        NOT NULL COMMENT 'user query',
    -- Conversation history, requirement section 4.5 "multi turn case". NULL keeps the case single turn.
    messages         JSON                 DEFAULT NULL COMMENT 'optional conversation history array',
    expected_answer  TEXT                 DEFAULT NULL COMMENT 'reference answer, consumed only by LLM-as-judge',
    anchor_type      VARCHAR(16) NOT NULL COMMENT 'SPAN/DOCUMENT',
    -- Anchored to doc_id rather than to a chunk id so the case survives a split strategy change; span is
    -- null for every element when anchor_type is DOCUMENT. See EvalEvidence.
    evidences        JSON        NOT NULL COMMENT 'array of {doc_id, span, annotated_version_id}',
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EVIDENCE_STALE/DEPRECATED',
    source           VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/DEBUG_PAGE/IMPORTED',
    note             VARCHAR(1024)        DEFAULT NULL COMMENT 'free text operator note',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version     INT         NOT NULL DEFAULT 0,
    deleted          TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_id (case_id),
    KEY idx_dataset_id (dataset_id),
    KEY idx_status (status),
    KEY idx_dataset_status (dataset_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='evaluation case: query, optional history, evidence anchors';

CREATE TABLE t_kb_eval_run
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    run_id               VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    dataset_id           VARCHAR(64)  NOT NULL COMMENT 'data set this run measures',
    kb_id                VARCHAR(64)  NOT NULL COMMENT 'owning knowledge base, denormalised for listing',
    dataset_revision     INT          NOT NULL COMMENT 'data set revision snapshotted at run creation',
    -- Hash of the active version set plus the embedding version plus the dictionary version, requirement
    -- section 4.6. Two runs only compare when both dataset_revision and this fingerprint match.
    corpus_fingerprint   VARCHAR(64)  NOT NULL COMMENT 'active corpus state this run measured against',
    -- One row of the EvalRetrievalConfig JSON, including its label and mode: the report page renders a
    -- run's column back from this document without reassembling it from separate columns.
    retrieval_config     JSON         NOT NULL COMMENT 'label, mode and retrieval parameters used by this run',
    judge_model          VARCHAR(128)          DEFAULT NULL COMMENT 'LLM-as-judge model, null when judging was off',
    judge_prompt_version VARCHAR(32)           DEFAULT NULL COMMENT 'judge prompt version, null when judging was off',
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    metrics              JSON                  DEFAULT NULL COMMENT 'grouped metrics with 95% CI, null until finished',
    case_total           INT          NOT NULL DEFAULT 0 COMMENT 'cases in the data set at run start',
    case_effective       INT          NOT NULL DEFAULT 0 COMMENT 'cases actually judged',
    case_stale           INT          NOT NULL DEFAULT 0 COMMENT 'cases skipped for stale evidence',
    case_degraded        INT          NOT NULL DEFAULT 0 COMMENT 'cases still degraded after the automatic retry',
    fail_reason          VARCHAR(1024)         DEFAULT NULL COMMENT 'set only when status is FAILED',
    started_at           DATETIME              DEFAULT NULL,
    finished_at          DATETIME              DEFAULT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version         INT          NOT NULL DEFAULT 0,
    deleted              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_id (run_id),
    KEY idx_dataset_id (dataset_id),
    KEY idx_status (status),
    KEY idx_dataset_created (dataset_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='evaluation run: one retrieval configuration measured once';

CREATE TABLE t_kb_eval_result
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    result_id          VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    run_id             VARCHAR(64) NOT NULL COMMENT 'owning run',
    case_id            VARCHAR(64) NOT NULL COMMENT 'case judged',
    hit                TINYINT     NOT NULL DEFAULT 0 COMMENT '1 when the case was recalled in the top K',
    hit_rank           INT                  DEFAULT NULL COMMENT 'one based rank of the first hit, null otherwise',
    overlap_ratios     JSON                 DEFAULT NULL COMMENT 'best aggregate coverage ratio per evidence',
    recalled_chunk_ids JSON                 DEFAULT NULL COMMENT 'chunk ids the top K returned for this case',
    degraded           JSON                 DEFAULT NULL COMMENT 'degradation markers observed while judging',
    retry_count        INT         NOT NULL DEFAULT 0 COMMENT 'automatic retries this case went through',
    judge_score        INT                  DEFAULT NULL COMMENT 'LLM-as-judge score, null when not judged',
    judge_reason       VARCHAR(2048)        DEFAULT NULL COMMENT 'LLM-as-judge free text rationale',
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version       INT         NOT NULL DEFAULT 0,
    deleted            TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_id (result_id),
    KEY idx_run_id (run_id),
    KEY idx_case_id (case_id),
    KEY idx_run_hit (run_id, hit)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='per case judgment of one evaluation run';
