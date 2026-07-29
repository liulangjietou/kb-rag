-- 里程碑 M2 的结构增量。
-- 新增分词器热更新通道背后的 ik 词典表，以及知识库级别的检索默认参数列。
-- 基线文件发布后不再修改，因此后续每一次变更都以独立的版本化迁移脚本落地。
SET NAMES utf8mb4;

CREATE TABLE t_kb_ik_dict
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    word         VARCHAR(64) NOT NULL COMMENT '下发给 ik 分词器的词条',
    dict_type    VARCHAR(16) NOT NULL COMMENT '词典类型：EXT 扩展词 / STOP 停用词',
    status       VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED，只有启用的词条会被下发',
    remark       VARCHAR(512)         DEFAULT NULL COMMENT '添加该词条的原因',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    -- 不分类型，一个词就是一条记录：同一个词不可能既是扩展词又是停用词，
    -- 而且管理接口只用词本身来定位一条记录。
    UNIQUE KEY uk_word (word),
    KEY idx_type_status (dict_type, status),
    -- 下发的词典按 word 排序，因此下发查询可以直接走这个索引。
    KEY idx_type_word (dict_type, word)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='ik 分词器词典';

ALTER TABLE t_kb_knowledge_base
    ADD COLUMN retrieval_config JSON DEFAULT NULL
        COMMENT '知识库级别的检索默认参数，可被请求参数覆盖'
        AFTER current_config_fingerprint;
