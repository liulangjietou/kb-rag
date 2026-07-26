-- Milestone M4a schema increment: the audit trail of the manual chunk operations.
-- The baseline and V2/V3 are never edited after release, so every later change arrives as its own migration.
--
-- t_kb_chunk needs no new column: parent_id, enabled and chunk_text_hash all exist since the baseline,
-- and the two knowledge base switches this milestone adds live inside the index_config JSON document
-- rather than in a column of their own, because they are configuration and not schema.

CREATE TABLE t_kb_annotation
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    annotation_id       VARCHAR(64) NOT NULL COMMENT 'business identifier exposed by the API',
    kb_id               VARCHAR(64) NOT NULL COMMENT 'owning knowledge base',
    doc_id              VARCHAR(64) NOT NULL COMMENT 'owning document, stable across versions',
    -- An annotation is only meaningful next to the chunks it was made against, because a new version may
    -- cut the document differently; chunk_text_hash is what lets a disable decision still be recognised.
    document_version_id VARCHAR(64) NOT NULL COMMENT 'document version the operation was performed against',
    chunk_id            VARCHAR(64) NOT NULL COMMENT 'operation target, the first source for a merge or split',
    annotation_type     VARCHAR(16) NOT NULL COMMENT 'EDIT/TOGGLE/MERGE/SPLIT',
    payload             JSON                 DEFAULT NULL COMMENT 'source ids, split offsets, excerpts, enabled state',
    chunk_text_hash     VARCHAR(64)          DEFAULT NULL COMMENT 'normalised text digest, drives cross version inheritance',
    inherit_status      VARCHAR(16) NOT NULL COMMENT 'NOT_INHERITED/AUTO_INHERITED/REDONE',
    -- Digest of target, kind and resulting content. Deliberately a plain index rather than a unique key:
    -- annotations are soft deleted with their document, and a unique index would reject the identical
    -- operation after the same file is uploaded again.
    idempotency_key     VARCHAR(64) NOT NULL COMMENT 'recognises a resubmitted operation',
    operator            VARCHAR(64) NOT NULL COMMENT 'console account that performed the operation',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version        INT         NOT NULL DEFAULT 0,
    deleted             TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_annotation_id (annotation_id),
    KEY idx_kb_id (kb_id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version_id (document_version_id),
    KEY idx_chunk_id (chunk_id),
    KEY idx_chunk_text_hash (chunk_text_hash),
    KEY idx_idempotency_key (idempotency_key),
    -- The inheritance pass walks the toggles of one document and the review list walks the open items,
    -- so both scans get the composite index they drive themselves from.
    KEY idx_doc_type (doc_id, annotation_type),
    KEY idx_doc_inherit (doc_id, inherit_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='manual chunk operation audit trail';
