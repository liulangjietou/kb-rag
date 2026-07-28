-- M10: the retrieval quality loop. Feedback stops being a log line and becomes a managed row that
-- can be converted into an evaluation case, and every online retrieval leaves an insight row so the
-- console can answer "what are people searching for that the corpus cannot serve".

CREATE TABLE t_kb_retrieval_feedback
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    feedback_id       VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the console',
    kb_id             VARCHAR(64)  NOT NULL COMMENT 'knowledge base the query ran against',
    -- Stored raw on purpose, unlike the insight digest: converting a feedback into an evaluation
    -- case replays the exact query, and a masked copy would create a case that never ran.
    query             TEXT         NOT NULL COMMENT 'query the debug page ran, raw, replayed on conversion',
    chunk_id          VARCHAR(64)  NOT NULL COMMENT 'chunk the verdict concerns',
    doc_id            VARCHAR(64)           DEFAULT NULL COMMENT 'owning document, resolved server side, null when the chunk was already deleted',
    verdict           VARCHAR(16)  NOT NULL COMMENT 'GOOD/BAD',
    status            VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/CONVERTED/DISMISSED',
    converted_case_id VARCHAR(64)           DEFAULT NULL COMMENT 'evaluation case created from this row, null until converted',
    note              VARCHAR(512)          DEFAULT NULL COMMENT 'free form operator note',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version      INT          NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_id (feedback_id),
    KEY idx_kb_id (kb_id),
    KEY idx_status (status),
    -- The console lists the open feedback of one knowledge base, which is exactly this pair.
    KEY idx_kb_status (kb_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='good/bad verdicts on debug results, convertible into evaluation cases';

CREATE TABLE t_kb_search_insight
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    insight_id   VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the console',
    kb_id        VARCHAR(64) NOT NULL COMMENT 'knowledge base the retrieval ran against',
    source       VARCHAR(16) NOT NULL COMMENT 'CONSOLE/OPEN_API, the boundary the call entered through',
    -- Masked by the section 4.2 rules and then truncated, the exact t_kb_api_audit_log discipline:
    -- an analytics table kept for 90 days must not become an unmasked copy of what users typed.
    query_digest VARCHAR(200)         DEFAULT NULL COMMENT 'masked and truncated query, never the raw text',
    query_hash   CHAR(64)    NOT NULL COMMENT 'SHA-256 of the normalized query, the grouping key of the miss report',
    result_count INT         NOT NULL DEFAULT 0 COMMENT 'nodes the call returned',
    top_score    DOUBLE               DEFAULT NULL COMMENT 'score of the first node, null on zero hits',
    zero_hit     TINYINT     NOT NULL DEFAULT 0 COMMENT 'derived from result_count = 0, the column the report scans',
    degraded     JSON                 DEFAULT NULL COMMENT 'degradation markers of this call',
    request_id   VARCHAR(64)          DEFAULT NULL COMMENT 'correlation id, links the row to logs and audit',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_insight_id (insight_id),
    KEY idx_kb_id (kb_id),
    KEY idx_query_hash (query_hash),
    -- The zero hit report of one knowledge base over a time window, which is exactly this triple.
    KEY idx_kb_zero_created (kb_id, zero_hit, created_at),
    -- The retention pass walks the expired rows.
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='one row per online retrieval, deleted after the retention window';
