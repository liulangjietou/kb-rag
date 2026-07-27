-- Milestone M8 schema increment: the chat import mapping profile table behind the console's import
-- mapping tab. The baseline and V1-V8 are never edited after release, so every later change arrives as
-- its own migration.
--
-- The requirement document announced this table for the first phase, but no migration ever created it:
-- phase one carried the mapping profiles as yml files next to the parser instead. The migration therefore
-- creates the table rather than adding the columns M8 needs to an existing one.

CREATE TABLE t_kb_source_mapping
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    mapping_id   VARCHAR(64)  NOT NULL COMMENT 'business identifier exposed by the API',
    -- Also the value the mapping_profile import parameter carried while the profiles were yml files, so
    -- an import addressing a built-in profile by its name keeps working after this table arrives.
    name         VARCHAR(128) NOT NULL COMMENT 'profile name, unique across built-in and custom rows',
    source_type  VARCHAR(16)  NOT NULL COMMENT 'CSV/XLSX/TXT/HTML, the export extension this profile reads',
    -- No description column: the body carries its own comment header, the console edits it in a text
    -- area, and a second field could only disagree with what the operator is already reading.
    profile_yaml MEDIUMTEXT   NOT NULL COMMENT 'full yaml body forwarded to the parser on every parse call',
    -- A seeded template is copied, never edited or deleted: the next release recalibrates it against a
    -- real export sample, which would silently revert an in place edit.
    is_builtin   TINYINT      NOT NULL DEFAULT 0 COMMENT '1 seeded template, 0 operator created',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mapping_id (mapping_id),
    -- The name is an address, not a label: the import parameter resolves a profile by it, so two rows
    -- sharing one name would leave the resolution to pick between them arbitrarily.
    UNIQUE KEY uk_name (name),
    KEY idx_source_type (source_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='chat import mapping profile forwarded to the parser';
