-- M14: external data source connectors. A registered source describes one S3/OSS compatible
-- bucket whose objects are scanned and funnelled into the ordinary upload chain; the item table
-- records the per-object binding and the outcome of its last sync, mirroring the weak binding of
-- the web source table - removing a registration never touches the documents it produced.

CREATE TABLE t_kb_ext_source
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    source_id        VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the console',
    kb_id            VARCHAR(64)  NOT NULL COMMENT 'knowledge base the fetched objects land in',
    source_type      VARCHAR(16)  NOT NULL COMMENT 'connector type routing key, s3 in this milestone',
    name             VARCHAR(128) NOT NULL COMMENT 'operator facing display name, unique per knowledge base',
    endpoint         VARCHAR(512) NOT NULL COMMENT 'service endpoint of the object store',
    region           VARCHAR(64)           DEFAULT NULL COMMENT 'optional region hint of the object store',
    bucket           VARCHAR(128) NOT NULL COMMENT 'bucket the scan lists',
    prefix           VARCHAR(512)          DEFAULT NULL COMMENT 'optional key prefix narrowing the scan',
    access_key       VARCHAR(256) NOT NULL COMMENT 'access key of the bucket credentials',
    -- Stored in clear on purpose: single admin console behind network isolation, the D17 premise.
    -- The read API never returns this column; envelope encryption belongs to the RBAC milestone.
    secret_key       VARCHAR(512) NOT NULL COMMENT 'secret key of the bucket credentials, never returned by the API',
    sync_enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 includes the source in the scheduled sync pass',
    last_sync_at     DATETIME              DEFAULT NULL COMMENT 'when the last sync attempt ran',
    last_sync_status VARCHAR(16)           DEFAULT NULL COMMENT 'SUCCESS/PARTIAL/FAILED',
    last_error       VARCHAR(512)          DEFAULT NULL COMMENT 'why the last sync failed or was partial, null on success',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version     INT          NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_id (source_id),
    -- One name per knowledge base; the name is what the operator recognises a source by.
    UNIQUE KEY uk_kb_name (kb_id, name),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='registered external object store sources feeding documents';

CREATE TABLE t_kb_ext_source_item
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    source_id       VARCHAR(64)   NOT NULL COMMENT 'owning external source business id',
    object_key      VARCHAR(1024) NOT NULL COMMENT 'object key inside the bucket',
    -- The equality key: a VARCHAR(1024) cannot carry a unique index, the digest of it can.
    object_key_hash CHAR(64)      NOT NULL COMMENT 'SHA-256 of the object key, the dedup and lookup key',
    etag            VARCHAR(128)           DEFAULT NULL COMMENT 'etag of the last ingested object body, the unchanged check',
    doc_id          VARCHAR(64)            DEFAULT NULL COMMENT 'document the object feeds, null until the first success',
    last_status     VARCHAR(16)            DEFAULT NULL COMMENT 'SUCCESS/UNCHANGED/SKIPPED/FAILED',
    last_error      VARCHAR(512)           DEFAULT NULL COMMENT 'why the last sync failed or was skipped, null on success',
    last_sync_at    DATETIME               DEFAULT NULL COMMENT 'when this object was last visited by a sync',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version    INT           NOT NULL DEFAULT 0,
    deleted         TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- One row per object per source; re-listing the same key updates the row instead of adding one.
    UNIQUE KEY uk_source_object (source_id, object_key_hash),
    KEY idx_source (source_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='per object sync outcome of an external source';
