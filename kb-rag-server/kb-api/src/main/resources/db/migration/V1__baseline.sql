-- Baseline schema of the knowledge base service (milestone M1).
-- Conventions applied to every business table: surrogate auto increment primary key, business key
-- as a prefixed varchar, created_at / updated_at, lock_version for optimistic locking, deleted for
-- soft deletion, utf8mb4, and an index on every status column that a compensation scan walks.
-- Later milestones add their tables through V2 and above; this file is never edited after release.

CREATE TABLE t_kb_knowledge_base
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id                      VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    name                       VARCHAR(128) NOT NULL COMMENT 'display name',
    description                VARCHAR(1024)         DEFAULT NULL COMMENT 'free text description',
    index_config               JSON                  DEFAULT NULL COMMENT 'split and embed parameters',
    current_config_fingerprint VARCHAR(64)           DEFAULT NULL COMMENT 'drives the document config_stale flag',
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version               INT          NOT NULL DEFAULT 0,
    deleted                    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_id (kb_id),
    KEY idx_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='knowledge base';

CREATE TABLE t_kb_document
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id             VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    kb_id              VARCHAR(64)  NOT NULL COMMENT 'owning knowledge base',
    file_name          VARCHAR(512) NOT NULL COMMENT 'original upload file name',
    file_ext           VARCHAR(16)  NOT NULL COMMENT 'lower case extension without the dot',
    file_size          BIGINT       NOT NULL DEFAULT 0 COMMENT 'original size in bytes',
    current_version_id VARCHAR(64)           DEFAULT NULL COMMENT 'active version pointer',
    process_status     VARCHAR(32)  NOT NULL COMMENT 'UPLOADED/PARSING/PARSE_FAILED/PARSED/INDEXING/INDEXED/INDEX_FAILED',
    config_stale       TINYINT      NOT NULL DEFAULT 0 COMMENT 'set when the active version used an older configuration',
    fail_reason        VARCHAR(1024)         DEFAULT NULL COMMENT 'classified failure cause',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version       INT          NOT NULL DEFAULT 0,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_id (doc_id),
    KEY idx_kb_id (kb_id),
    KEY idx_process_status (process_status),
    KEY idx_kb_status (kb_id, process_status),
    KEY idx_config_stale (kb_id, config_stale)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='document master record';

CREATE TABLE t_kb_document_version
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    version_id        VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    doc_id            VARCHAR(64) NOT NULL COMMENT 'owning document',
    version           VARCHAR(16) NOT NULL COMMENT 'major.minor, starts at 1.0',
    minio_object      VARCHAR(512)         DEFAULT NULL COMMENT 'object key of the original file',
    parsed_object     VARCHAR(512)         DEFAULT NULL COMMENT 'object key of the parsed markdown',
    content_hash      VARCHAR(64)          DEFAULT NULL COMMENT 'SHA-256 of the original byte stream',
    parse_fingerprint VARCHAR(64)          DEFAULT NULL COMMENT 'fingerprint of the parse stage inputs',
    chunk_fingerprint VARCHAR(64)          DEFAULT NULL COMMENT 'fingerprint of the split stage inputs',
    embedding_version VARCHAR(64)          DEFAULT NULL COMMENT 'embedding model this build used',
    status            VARCHAR(32) NOT NULL COMMENT 'BUILDING/BUILD_FAILED/READY/ACTIVE/ARCHIVED',
    active_flag       TINYINT              DEFAULT NULL COMMENT '1 for the single active version, NULL otherwise',
    changelog         VARCHAR(1024)        DEFAULT NULL COMMENT 'change note shown in the version list',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version      INT         NOT NULL DEFAULT 0,
    deleted           TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_id (version_id),
    UNIQUE KEY uk_doc_version (doc_id, version),
    -- MySQL treats NULL values as distinct in a unique index, so this enforces at most one active
    -- version per document while leaving every other row unconstrained.
    UNIQUE KEY uk_doc_active (doc_id, active_flag),
    KEY idx_doc_id (doc_id),
    KEY idx_status (status),
    KEY idx_content_hash (content_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='document version';

CREATE TABLE t_kb_chunk
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    chunk_id            VARCHAR(64) NOT NULL COMMENT 'business identifier, also the engine primary key',
    kb_id               VARCHAR(64) NOT NULL COMMENT 'owning knowledge base',
    doc_id              VARCHAR(64) NOT NULL COMMENT 'owning document',
    document_version_id VARCHAR(64) NOT NULL COMMENT 'owning version, mandatory for retrieval isolation',
    content             MEDIUMTEXT COMMENT 'chunk text, MySQL is the fact source',
    chunk_text_hash     VARCHAR(64)          DEFAULT NULL COMMENT 'SHA-256 of the normalised text',
    parent_id           VARCHAR(64)          DEFAULT NULL COMMENT 'parent chunk for parent child splitting',
    seq                 INT         NOT NULL DEFAULT 0 COMMENT 'order inside the version',
    chunk_type          VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/CHAT_LOG',
    enabled             TINYINT     NOT NULL DEFAULT 1 COMMENT '0 excludes the chunk from retrieval',
    embedding_status    VARCHAR(32) NOT NULL COMMENT 'PENDING/DONE/FAILED/SKIPPED',
    metadata            JSON                 DEFAULT NULL COMMENT 'non filterable metadata, MySQL only',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version        INT         NOT NULL DEFAULT 0,
    deleted             TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_id (chunk_id),
    KEY idx_kb_id (kb_id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version_id (document_version_id),
    KEY idx_chunk_text_hash (chunk_text_hash),
    KEY idx_parent_id (parent_id),
    KEY idx_embedding_status (embedding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='chunk fact source';

CREATE TABLE t_kb_chunk_index_sync
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    chunk_id            VARCHAR(64)  NOT NULL COMMENT 'chunk business id',
    physical_index_name VARCHAR(255) NOT NULL COMMENT 'physical index or collection name',
    engine              VARCHAR(16)  NOT NULL COMMENT 'es/qdrant',
    status              VARCHAR(32)  NOT NULL COMMENT 'PENDING/SYNCED/FAILED',
    retry_count         INT          NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version        INT          NOT NULL DEFAULT 0,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_index (chunk_id, physical_index_name),
    KEY idx_status (status),
    KEY idx_index_status (physical_index_name, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='synchronization state per chunk and physical index';

CREATE TABLE t_kb_index_registry
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id               VARCHAR(64)  NOT NULL COMMENT 'owning knowledge base',
    engine              VARCHAR(16)  NOT NULL COMMENT 'es/qdrant',
    physical_index_name VARCHAR(255) NOT NULL COMMENT 'three segment physical name',
    alias_name          VARCHAR(255) NOT NULL COMMENT 'alias every read and write goes through',
    is_current          TINYINT      NOT NULL DEFAULT 0 COMMENT '1 when the alias points here',
    embedding_provider  VARCHAR(64)           DEFAULT NULL,
    embedding_model     VARCHAR(128)          DEFAULT NULL,
    embedding_version   VARCHAR(64)           DEFAULT NULL COMMENT 'embedding segment of the physical name',
    snapshot_version    VARCHAR(32)           DEFAULT NULL COMMENT 'snapshot segment, v1 in M1',
    schema_version      VARCHAR(32)           DEFAULT NULL COMMENT 'engine field set version',
    status              VARCHAR(32)  NOT NULL COMMENT 'BUILDING/ACTIVE/PENDING_CLEANUP',
    task_id             VARCHAR(64)           DEFAULT NULL COMMENT 'task that created this index',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version        INT          NOT NULL DEFAULT 0,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_physical_index_name (physical_index_name),
    KEY idx_kb_id (kb_id),
    KEY idx_alias (alias_name),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='physical index and alias registry';

CREATE TABLE t_kb_task
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    task_id      VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    task_type    VARCHAR(32) NOT NULL COMMENT 'PARSE/INDEX/REBUILD/CLEANUP',
    biz_id       VARCHAR(64) NOT NULL COMMENT 'entity the task operates on',
    status       VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    retry_count  INT         NOT NULL DEFAULT 0,
    fail_reason  VARCHAR(1024)        DEFAULT NULL COMMENT 'classified failure cause',
    progress     INT         NOT NULL DEFAULT 0 COMMENT 'completion percentage',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_biz_id (biz_id),
    KEY idx_status (status),
    KEY idx_type_status (task_type, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='asynchronous task';

CREATE TABLE t_kb_admin_user
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(64)  NOT NULL COMMENT 'login name',
    password_hash        VARCHAR(128) NOT NULL COMMENT 'BCrypt hash, never returned by the API',
    must_change_password TINYINT      NOT NULL DEFAULT 1 COMMENT '1 forces a rotation after login',
    last_login_at        DATETIME              DEFAULT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version         INT          NOT NULL DEFAULT 0,
    deleted              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='console administrator account';

CREATE TABLE t_kb_system_config
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    config_key   VARCHAR(128) NOT NULL COMMENT 'configuration key',
    config_value TEXT COMMENT 'configuration value, JSON for structured entries',
    description  VARCHAR(512)          DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='global system settings';

CREATE TABLE t_kb_login_audit
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    username     VARCHAR(64) NOT NULL COMMENT 'submitted user name, recorded even when unknown',
    ip           VARCHAR(64)          DEFAULT NULL COMMENT 'source address',
    success      TINYINT     NOT NULL DEFAULT 0,
    reason       VARCHAR(32)          DEFAULT NULL COMMENT 'SUCCESS/USER_NOT_FOUND/BAD_PASSWORD/ACCOUNT_LOCKED',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT         NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_username_created (username, created_at),
    KEY idx_ip_created (ip, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='console login audit and brute force counter source';
