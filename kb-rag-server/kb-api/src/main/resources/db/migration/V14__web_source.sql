-- M12: URL import and incremental sync. A registered web source is the bridge between a URL and
-- the document its fetches produce: the fetch itself funnels into the ordinary upload chain, so
-- this table only records the binding, the sync switch and the outcome of the last fetch.

CREATE TABLE t_kb_web_source
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    source_id         VARCHAR(64)   NOT NULL COMMENT 'business identifier exposed by the console',
    kb_id             VARCHAR(64)   NOT NULL COMMENT 'knowledge base the fetched pages land in',
    url               VARCHAR(2048) NOT NULL COMMENT 'registered page address, http or https',
    -- The equality key: a VARCHAR(2048) cannot carry a unique index, the digest of it can.
    url_hash          CHAR(64)      NOT NULL COMMENT 'SHA-256 of the url, the dedup and lookup key',
    doc_id            VARCHAR(64)            DEFAULT NULL COMMENT 'document the fetches feed, null until the first success',
    file_name         VARCHAR(255)           DEFAULT NULL COMMENT 'derived stable file name the upload chain sees',
    sync_enabled      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 includes the source in the scheduled sync pass',
    last_content_hash CHAR(64)               DEFAULT NULL COMMENT 'SHA-256 of the last fetched body, the unchanged check',
    last_fetch_at     DATETIME               DEFAULT NULL COMMENT 'when the last sync attempt ran',
    last_fetch_status VARCHAR(16)            DEFAULT NULL COMMENT 'SUCCESS/UNCHANGED/SKIPPED/FAILED',
    last_error        VARCHAR(512)           DEFAULT NULL COMMENT 'why the last sync failed or was skipped, null on success',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version      INT           NOT NULL DEFAULT 0,
    deleted           TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_id (source_id),
    -- One registration per URL per knowledge base; other knowledge bases may register the same URL.
    UNIQUE KEY uk_kb_url (kb_id, url_hash),
    KEY idx_kb_id (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='registered web page sources feeding documents via URL import';
