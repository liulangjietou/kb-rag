-- 里程碑 M3 的结构增量：多模态资产，以及文档的逻辑来源标识。
-- 基线与 V2 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
SET NAMES utf8mb4;

CREATE TABLE t_kb_image_asset
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    image_id            VARCHAR(64) NOT NULL COMMENT '全局唯一的业务标识',
    -- 解析器是按文档给图片编号的（img_1、img_2），这种编号扛不住全局唯一约束，
    -- 因此占位符里的标识单独用一列存放，与全局标识并列。
    source_image_id     VARCHAR(64) NOT NULL COMMENT '[[IMAGE:id]] 占位符中使用的标识',
    kb_id               VARCHAR(64) NOT NULL COMMENT '所属知识库',
    doc_id              VARCHAR(64) NOT NULL COMMENT '所属文档',
    document_version_id VARCHAR(64) NOT NULL COMMENT '所属文档版本',
    page_no             INT                  DEFAULT NULL COMMENT '页码，从 1 开始；无分页概念的格式为 null',
    kind                VARCHAR(16) NOT NULL COMMENT '图片来源：EMBEDDED 内嵌 / PAGE_RENDER 整页渲染 / STANDALONE 独立文件',
    object_key          VARCHAR(512) NOT NULL COMMENT '二进制内容的对象存储 key',
    media_type          VARCHAR(64)          DEFAULT NULL COMMENT '二进制内容的 MIME 类型',
    bytes               BIGINT      NOT NULL DEFAULT 0 COMMENT '二进制内容的字节数',
    text_proxy          MEDIUMTEXT           DEFAULT NULL COMMENT '视觉模型产出的图片描述与文字转录',
    status              VARCHAR(16) NOT NULL COMMENT '处理状态：PENDING/DONE/SKIPPED/FAILED',
    fail_reason         VARCHAR(1024)        DEFAULT NULL COMMENT 'status 为 FAILED 时归类后的失败原因',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_image_id (image_id),
    -- 占位符标识只需在单个版本内唯一，而这也正是流水线把图片代理文本回填进正文时所做的查询。
    UNIQUE KEY uk_version_source (document_version_id, source_image_id),
    KEY idx_kb_id (kb_id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version_id (document_version_id),
    -- 补偿任务会扫描还没有代理文本的资产，因此 status 和其他被扫描驱动的列一样建索引。
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='图片资产及其文本代理';

-- 一次聊天会话是逻辑文档而不是一个文件：它的身份要能扛住重命名和二次导出，
-- 而用于展示的文件名做不到这一点。
ALTER TABLE t_kb_document
    ADD COLUMN source_key VARCHAR(255) DEFAULT NULL
        COMMENT '逻辑来源标识，聊天导入为 chat:{session_id}，普通上传为 null'
        AFTER fail_reason,
    ADD KEY idx_kb_source_key (kb_id, source_key);
