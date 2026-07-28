-- M11: content governance. A document stops being retrievable the moment it is uploaded and
-- becomes retrievable when governance says so: published, inside its validity window and not in
-- the trash. Every existing row defaults to the permissive state, so upgrading changes nothing.

ALTER TABLE t_kb_document
    ADD COLUMN publish_status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED' AFTER process_status,
    ADD COLUMN review_note    VARCHAR(512)         DEFAULT NULL COMMENT 'latest rejection reason, cleared on approval' AFTER publish_status,
    ADD COLUMN effective_at   DATETIME             DEFAULT NULL COMMENT 'retrievable from this instant, null means no lower bound' AFTER review_note,
    ADD COLUMN expires_at     DATETIME             DEFAULT NULL COMMENT 'retrievable before this instant, null means no upper bound' AFTER effective_at,
    ADD COLUMN trashed        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 while the document sits in the recycle bin' AFTER expires_at,
    ADD COLUMN trashed_at     DATETIME             DEFAULT NULL COMMENT 'when the document entered the recycle bin, drives the purge pass' AFTER trashed,
    ADD KEY idx_kb_publish (kb_id, publish_status),
    ADD KEY idx_kb_trashed (kb_id, trashed),
    ADD KEY idx_trashed_at (trashed_at);

ALTER TABLE t_kb_knowledge_base
    ADD COLUMN review_required TINYINT NOT NULL DEFAULT 0 COMMENT '1 makes a new document start as DRAFT instead of PUBLISHED' AFTER retrieval_config;
