-- M14：外部数据源连接器。一条已注册的数据源描述一个兼容 S3/OSS 的桶，其中的对象会被扫描出来
-- 并汇入普通的上传链路；条目表记录逐对象的绑定关系及其最近一次同步结果。它沿用网页源表那种弱绑定：
-- 删除一条注册，绝不牵动它曾经产出的文档。
SET NAMES utf8mb4;

CREATE TABLE t_kb_ext_source
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    source_id        VARCHAR(64)  NOT NULL COMMENT '控制台对外暴露的业务标识',
    kb_id            VARCHAR(64)  NOT NULL COMMENT '拉取到的对象所落入的知识库',
    source_type      VARCHAR(16)  NOT NULL COMMENT '连接器类型路由键，本里程碑固定为 s3',
    name             VARCHAR(128) NOT NULL COMMENT '面向运营人员的展示名称，知识库内唯一',
    endpoint         VARCHAR(512) NOT NULL COMMENT '对象存储的服务端点',
    region           VARCHAR(64)           DEFAULT NULL COMMENT '对象存储的可选 region 提示',
    bucket           VARCHAR(128) NOT NULL COMMENT '扫描所列举的桶名',
    prefix           VARCHAR(512)          DEFAULT NULL COMMENT '可选的 key 前缀，用于收窄扫描范围',
    access_key       VARCHAR(256) NOT NULL COMMENT '桶凭据的 access key',
    -- 刻意明文存储：单管理员控制台、部署在网络隔离环境内，这是 D17 的前提。
    -- 读接口永不回传这一列；信封加密属于 RBAC 里程碑的范围。
    secret_key       VARCHAR(512) NOT NULL COMMENT '桶凭据的 secret key，接口永不回传',
    sync_enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '置 1 时该数据源纳入定时同步任务',
    last_sync_at     DATETIME              DEFAULT NULL COMMENT '最近一次同步尝试的执行时间',
    last_sync_status VARCHAR(16)           DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS 全部成功 / PARTIAL 部分成功 / FAILED 失败',
    last_error       VARCHAR(512)          DEFAULT NULL COMMENT '最近一次同步失败或仅部分成功的原因，全部成功时为 null',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_id (source_id),
    -- 同一个知识库下名称唯一；运营人员正是靠这个名称来辨认一条数据源的。
    UNIQUE KEY uk_kb_name (kb_id, name),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='已注册的外部对象存储数据源，用于喂养文档';

CREATE TABLE t_kb_ext_source_item
(
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    source_id       VARCHAR(64)   NOT NULL COMMENT '所属外部数据源的业务标识',
    object_key      VARCHAR(1024) NOT NULL COMMENT '桶内的对象 key',
    -- 等值比较用的键：VARCHAR(1024) 撑不起唯一索引，它的摘要可以。
    object_key_hash CHAR(64)      NOT NULL COMMENT '对象 key 的 SHA-256，去重与查找键',
    etag            VARCHAR(128)           DEFAULT NULL COMMENT '最近一次入库的对象正文 etag，用于判断内容是否未变',
    doc_id          VARCHAR(64)            DEFAULT NULL COMMENT '该对象所喂养的文档，首次成功前为 null',
    last_status     VARCHAR(16)            DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS/UNCHANGED/SKIPPED/FAILED',
    last_error      VARCHAR(512)           DEFAULT NULL COMMENT '最近一次同步失败或被跳过的原因，成功时为 null',
    last_sync_at    DATETIME               DEFAULT NULL COMMENT '该对象最近一次被同步任务访问的时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version    INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    -- 一个数据源下每个对象一行；再次列举到同一个 key 时更新原行，而不是新增一行。
    UNIQUE KEY uk_source_object (source_id, object_key_hash),
    KEY idx_source (source_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='外部数据源逐对象的同步结果';
