-- 里程碑 M4a 的结构增量：人工分片操作的审计流水。
-- 基线与 V2/V3 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
--
-- t_kb_chunk 不需要新增列：parent_id、enabled 和 chunk_text_hash 从基线起就存在；
-- 本里程碑引入的两个知识库开关放在 index_config 这个 JSON 文档里，而不是各占一列，
-- 因为它们属于配置而不是表结构。
SET NAMES utf8mb4;

CREATE TABLE t_kb_annotation
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    annotation_id       VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    kb_id               VARCHAR(64) NOT NULL COMMENT '所属知识库',
    doc_id              VARCHAR(64) NOT NULL COMMENT '所属文档，跨版本保持稳定',
    -- 一条标注只有放在它当初针对的那批分片旁边才有意义，因为新版本可能会用不同的方式切分文档；
    -- 靠 chunk_text_hash，一次「停用」决定在新版本里才仍然可以被识别出来。
    document_version_id VARCHAR(64) NOT NULL COMMENT '执行本次操作时所针对的文档版本',
    chunk_id            VARCHAR(64) NOT NULL COMMENT '操作目标分片；合并或拆分时为第一个源分片',
    annotation_type     VARCHAR(16) NOT NULL COMMENT '操作类型：EDIT 编辑 / TOGGLE 启停 / MERGE 合并 / SPLIT 拆分',
    payload             JSON                 DEFAULT NULL COMMENT '操作载荷：源分片 id、拆分偏移、摘录片段、启停状态',
    chunk_text_hash     VARCHAR(64)          DEFAULT NULL COMMENT '归一化文本摘要，驱动跨版本的标注继承',
    inherit_status      VARCHAR(16) NOT NULL COMMENT '继承状态：NOT_INHERITED 未继承 / AUTO_INHERITED 自动继承 / REDONE 已重做',
    -- 由操作目标、操作类型和结果内容摘要而成。这里刻意用普通索引而不是唯一索引：
    -- 标注会随文档一起被逻辑删除，唯一索引会让同一个文件二次上传后无法再执行同样的操作。
    idempotency_key     VARCHAR(64) NOT NULL COMMENT '幂等键，用于识别重复提交的同一次操作',
    operator            VARCHAR(64) NOT NULL COMMENT '执行本次操作的控制台账号',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_annotation_id (annotation_id),
    KEY idx_kb_id (kb_id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version_id (document_version_id),
    KEY idx_chunk_id (chunk_id),
    KEY idx_chunk_text_hash (chunk_text_hash),
    KEY idx_idempotency_key (idempotency_key),
    -- 继承任务扫描单个文档的启停标注，待办列表扫描单个文档的未处理项，
    -- 两个扫描各自拿到能直接驱动自己的联合索引。
    KEY idx_doc_type (doc_id, annotation_type),
    KEY idx_doc_inherit (doc_id, inherit_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='人工分片操作审计流水';
