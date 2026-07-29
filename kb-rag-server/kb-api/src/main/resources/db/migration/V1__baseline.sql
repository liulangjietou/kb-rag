-- 知识库服务的基线表结构（里程碑 M1）。
-- 所有业务表统一遵守的约定：自增代理主键、带前缀的 varchar 业务主键、created_at / updated_at、
-- lock_version 乐观锁、deleted 逻辑删除、utf8mb4 字符集，以及补偿扫描会走的每个状态列都建索引。
-- 后续里程碑通过 V2 及以上的迁移脚本追加自己的表；本文件发布后不再修改。
SET NAMES utf8mb4;

CREATE TABLE t_kb_knowledge_base
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    kb_id                      VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    name                       VARCHAR(128) NOT NULL COMMENT '展示名称',
    description                VARCHAR(1024)         DEFAULT NULL COMMENT '自由文本描述',
    index_config               JSON                  DEFAULT NULL COMMENT '切分与向量化参数',
    current_config_fingerprint VARCHAR(64)           DEFAULT NULL COMMENT '配置指纹，驱动文档的 config_stale 标记',
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version               INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted                    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_id (kb_id),
    KEY idx_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='知识库';

CREATE TABLE t_kb_document
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    doc_id             VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    kb_id              VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    file_name          VARCHAR(512) NOT NULL COMMENT '上传时的原始文件名',
    file_ext           VARCHAR(16)  NOT NULL COMMENT '小写扩展名，不含点号',
    file_size          BIGINT       NOT NULL DEFAULT 0 COMMENT '原始文件字节数',
    current_version_id VARCHAR(64)           DEFAULT NULL COMMENT '生效版本指针',
    process_status     VARCHAR(32)  NOT NULL COMMENT '处理状态：UPLOADED/PARSING/PARSE_FAILED/PARSED/INDEXING/INDEXED/INDEX_FAILED',
    config_stale       TINYINT      NOT NULL DEFAULT 0 COMMENT '生效版本使用了旧配置时置 1',
    fail_reason        VARCHAR(1024)         DEFAULT NULL COMMENT '归类后的失败原因',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_id (doc_id),
    KEY idx_kb_id (kb_id),
    KEY idx_process_status (process_status),
    KEY idx_kb_status (kb_id, process_status),
    KEY idx_config_stale (kb_id, config_stale)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='文档主记录';

CREATE TABLE t_kb_document_version
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    version_id        VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    doc_id            VARCHAR(64) NOT NULL COMMENT '所属文档',
    version           VARCHAR(16) NOT NULL COMMENT '版本号 major.minor，从 1.0 开始',
    minio_object      VARCHAR(512)         DEFAULT NULL COMMENT '原始文件的对象存储 key',
    parsed_object     VARCHAR(512)         DEFAULT NULL COMMENT '解析产物 markdown 的对象存储 key',
    content_hash      VARCHAR(64)          DEFAULT NULL COMMENT '原始字节流的 SHA-256',
    parse_fingerprint VARCHAR(64)          DEFAULT NULL COMMENT '解析阶段入参的指纹',
    chunk_fingerprint VARCHAR(64)          DEFAULT NULL COMMENT '切分阶段入参的指纹',
    embedding_version VARCHAR(64)          DEFAULT NULL COMMENT '本次构建使用的向量模型',
    status            VARCHAR(32) NOT NULL COMMENT '版本状态：BUILDING/BUILD_FAILED/READY/ACTIVE/ARCHIVED',
    active_flag       TINYINT              DEFAULT NULL COMMENT '唯一生效版本置 1，其余为 NULL',
    changelog         VARCHAR(1024)        DEFAULT NULL COMMENT '版本列表展示的变更说明',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_id (version_id),
    UNIQUE KEY uk_doc_version (doc_id, version),
    -- MySQL 的唯一索引把多个 NULL 视为互不相同，因此这个索引既能保证「一个文档最多一个生效版本」，
    -- 又不会约束到其余任何行。
    UNIQUE KEY uk_doc_active (doc_id, active_flag),
    KEY idx_doc_id (doc_id),
    KEY idx_status (status),
    KEY idx_content_hash (content_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='文档版本';

CREATE TABLE t_kb_chunk
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chunk_id            VARCHAR(64) NOT NULL COMMENT '业务标识，同时也是检索引擎侧的主键',
    kb_id               VARCHAR(64) NOT NULL COMMENT '所属知识库',
    doc_id              VARCHAR(64) NOT NULL COMMENT '所属文档',
    document_version_id VARCHAR(64) NOT NULL COMMENT '所属文档版本，检索隔离必填',
    content             MEDIUMTEXT COMMENT '分片正文，MySQL 是事实源',
    chunk_text_hash     VARCHAR(64)          DEFAULT NULL COMMENT '归一化文本的 SHA-256',
    parent_id           VARCHAR(64)          DEFAULT NULL COMMENT '父子切分时的父分片 id',
    seq                 INT         NOT NULL DEFAULT 0 COMMENT '在本版本内的排序序号',
    chunk_type          VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT '分片类型：TEXT/IMAGE/CHAT_LOG',
    enabled             TINYINT     NOT NULL DEFAULT 1 COMMENT '置 0 时该分片不参与检索',
    embedding_status    VARCHAR(32) NOT NULL COMMENT '向量化状态：PENDING/DONE/FAILED/SKIPPED',
    metadata            JSON                 DEFAULT NULL COMMENT '不可过滤的元数据，仅存 MySQL',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
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
  COLLATE = utf8mb4_general_ci COMMENT ='分片事实源';

CREATE TABLE t_kb_chunk_index_sync
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chunk_id            VARCHAR(64)  NOT NULL COMMENT '分片业务 id',
    physical_index_name VARCHAR(255) NOT NULL COMMENT '物理索引名或集合名',
    engine              VARCHAR(16)  NOT NULL COMMENT '引擎类型：es/qdrant',
    status              VARCHAR(32)  NOT NULL COMMENT '同步状态：PENDING/SYNCED/FAILED',
    retry_count         INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_index (chunk_id, physical_index_name),
    KEY idx_status (status),
    KEY idx_index_status (physical_index_name, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='分片在各物理索引上的同步状态';

CREATE TABLE t_kb_index_registry
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    kb_id               VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    engine              VARCHAR(16)  NOT NULL COMMENT '引擎类型：es/qdrant',
    physical_index_name VARCHAR(255) NOT NULL COMMENT '三段式物理索引名',
    alias_name          VARCHAR(255) NOT NULL COMMENT '别名，所有读写都经由它',
    is_current          TINYINT      NOT NULL DEFAULT 0 COMMENT '别名当前指向本行时置 1',
    embedding_provider  VARCHAR(64)           DEFAULT NULL COMMENT '向量模型提供方',
    embedding_model     VARCHAR(128)          DEFAULT NULL COMMENT '向量模型名称',
    embedding_version   VARCHAR(64)           DEFAULT NULL COMMENT '物理索引名中的向量模型段',
    snapshot_version    VARCHAR(32)           DEFAULT NULL COMMENT '物理索引名中的快照段，M1 固定为 v1',
    schema_version      VARCHAR(32)           DEFAULT NULL COMMENT '引擎侧字段集版本',
    status              VARCHAR(32)  NOT NULL COMMENT '索引状态：BUILDING/ACTIVE/PENDING_CLEANUP',
    task_id             VARCHAR(64)           DEFAULT NULL COMMENT '创建本索引的任务 id',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_physical_index_name (physical_index_name),
    KEY idx_kb_id (kb_id),
    KEY idx_alias (alias_name),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='物理索引与别名注册表';

CREATE TABLE t_kb_task
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    task_id      VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    task_type    VARCHAR(32) NOT NULL COMMENT '任务类型：PARSE/INDEX/REBUILD/CLEANUP',
    biz_id       VARCHAR(64) NOT NULL COMMENT '任务操作的业务实体 id',
    status       VARCHAR(32) NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED',
    retry_count  INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    fail_reason  VARCHAR(1024)        DEFAULT NULL COMMENT '归类后的失败原因',
    progress     INT         NOT NULL DEFAULT 0 COMMENT '完成百分比',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_biz_id (biz_id),
    KEY idx_status (status),
    KEY idx_type_status (task_type, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='异步任务';

CREATE TABLE t_kb_admin_user
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    username             VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash        VARCHAR(128) NOT NULL COMMENT 'BCrypt 摘要，接口永不回传',
    must_change_password TINYINT      NOT NULL DEFAULT 1 COMMENT '置 1 时登录后强制改密',
    last_login_at        DATETIME              DEFAULT NULL COMMENT '最近一次登录成功时间',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='控制台管理员账号';

CREATE TABLE t_kb_system_config
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    config_key   VARCHAR(128) NOT NULL COMMENT '配置项键名',
    config_value TEXT COMMENT '配置项值，结构化配置存 JSON',
    description  VARCHAR(512)          DEFAULT NULL COMMENT '配置项说明',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='全局系统配置';

CREATE TABLE t_kb_login_audit
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    username     VARCHAR(64) NOT NULL COMMENT '提交的用户名，账号不存在时也照样记录',
    ip           VARCHAR(64)          DEFAULT NULL COMMENT '来源地址',
    success      TINYINT     NOT NULL DEFAULT 0 COMMENT '本次登录是否成功，1 成功',
    reason       VARCHAR(32)          DEFAULT NULL COMMENT '结果原因：SUCCESS/USER_NOT_FOUND/BAD_PASSWORD/ACCOUNT_LOCKED',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_username_created (username, created_at),
    KEY idx_ip_created (ip, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='控制台登录审计，同时是暴力破解计数的数据来源';
