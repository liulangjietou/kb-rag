-- Milestone M2 schema increment.
-- Adds the ik dictionary table behind the tokenizer hot update channel and the knowledge base level
-- retrieval defaults column. The baseline file is never edited after release, so every later change
-- arrives as its own versioned migration.

CREATE TABLE t_kb_ik_dict
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    word         VARCHAR(64) NOT NULL COMMENT 'dictionary term served to the ik tokenizer',
    dict_type    VARCHAR(16) NOT NULL COMMENT 'EXT/STOP',
    status       VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED, only enabled terms are served',
    remark       VARCHAR(512)         DEFAULT NULL COMMENT 'why the term was added',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- One term is one entry regardless of its kind: a word cannot be both an extension term and a
    -- stop word, and the management API addresses an entry by its word alone.
    UNIQUE KEY uk_word (word),
    KEY idx_type_status (dict_type, status),
    -- The rendered dictionary is ordered by word, so the serving query reads this index directly.
    KEY idx_type_word (dict_type, word)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='ik tokenizer dictionary';

ALTER TABLE t_kb_knowledge_base
    ADD COLUMN retrieval_config JSON DEFAULT NULL
        COMMENT 'knowledge base level retrieval defaults, overridden by request parameters'
        AFTER current_config_fingerprint;
