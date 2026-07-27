-- Console session tokens move from process memory into the database, so a restart of the
-- single instance no longer ends every session. The 24 hour TTL semantics stay unchanged.
--
-- Only the SHA-256 digest of the token is stored, mirroring t_kb_api_key: a database dump can
-- not be replayed against the console because the plaintext only ever lives in the browser.

CREATE TABLE t_kb_auth_token
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    token_hash   VARCHAR(64)  NOT NULL COMMENT 'SHA-256 digest of the opaque bearer token, the only stored form',
    username     VARCHAR(64)  NOT NULL COMMENT 'console account the session belongs to',
    expires_at   DATETIME     NOT NULL COMMENT 'absolute expiry, issued time plus the configured TTL',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lock_version INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    -- revokeAll walks the sessions of one account after a password change,
    -- the expiry purge on login walks the expired rows.
    KEY idx_username (username),
    KEY idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='console session tokens, survives a process restart';
