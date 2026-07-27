-- Milestone M3 schema increment: multimodal assets and the logical source identity of a document.
-- The baseline and V2 are never edited after release, so every later change arrives as its own migration.

CREATE TABLE t_kb_image_asset
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    image_id            VARCHAR(64) NOT NULL COMMENT 'globally unique business identifier',
    -- The parser numbers its images per document (img_1, img_2), which cannot carry a global unique key,
    -- so the placeholder identifier lives in its own column next to the global one.
    source_image_id     VARCHAR(64) NOT NULL COMMENT 'identifier used inside the [[IMAGE:id]] placeholder',
    kb_id               VARCHAR(64) NOT NULL COMMENT 'owning knowledge base',
    doc_id              VARCHAR(64) NOT NULL COMMENT 'owning document',
    document_version_id VARCHAR(64) NOT NULL COMMENT 'owning document version',
    page_no             INT                  DEFAULT NULL COMMENT 'one based page, null for formats without pages',
    kind                VARCHAR(16) NOT NULL COMMENT 'EMBEDDED/PAGE_RENDER/STANDALONE',
    object_key          VARCHAR(512) NOT NULL COMMENT 'object storage key of the binary',
    media_type          VARCHAR(64)          DEFAULT NULL COMMENT 'MIME type of the binary',
    bytes               BIGINT      NOT NULL DEFAULT 0 COMMENT 'size of the binary in bytes',
    text_proxy          MEDIUMTEXT           DEFAULT NULL COMMENT 'description and transcription from the vision model',
    status              VARCHAR(16) NOT NULL COMMENT 'PENDING/DONE/SKIPPED/FAILED',
    fail_reason         VARCHAR(1024)        DEFAULT NULL COMMENT 'classified cause when status is FAILED',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version        INT         NOT NULL DEFAULT 0,
    deleted             TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_image_id (image_id),
    -- The placeholder identifier only has to be unique inside one version, which is also the lookup the
    -- pipeline performs when it splices the proxies back into the text.
    UNIQUE KEY uk_version_source (document_version_id, source_image_id),
    KEY idx_kb_id (kb_id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version_id (document_version_id),
    -- A backfill pass walks the assets that carry no text yet, so the status is indexed like every other
    -- column a scan drives itself from.
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='image asset and its textual proxy';

-- A chat conversation is a logical document rather than a file: the identity has to survive a rename and a
-- second export, which the display file name cannot do.
ALTER TABLE t_kb_document
    ADD COLUMN source_key VARCHAR(255) DEFAULT NULL
        COMMENT 'logical source identity, chat:{session_id} for a chat import, null for a plain upload'
        AFTER fail_reason,
    ADD KEY idx_kb_source_key (kb_id, source_key);
