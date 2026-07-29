-- 里程碑 M8 的结构增量：控制台「导入映射」页签背后的聊天导入映射模板表。
-- 基线与 V1-V8 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
--
-- 需求文档在一期就提出了这张表，但当时没有任何迁移脚本创建过它：一期是把映射模板以 yml 文件的
-- 形式放在解析器旁边的。因此这个迁移是新建表，而不是在既有表上补 M8 需要的列。
SET NAMES utf8mb4;

CREATE TABLE t_kb_source_mapping
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    mapping_id   VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    -- 模板还是 yml 文件的时候，导入参数 mapping_profile 传的也是这个值，
    -- 因此按名称寻址内置模板的导入请求，在这张表落地之后依然可以正常工作。
    name         VARCHAR(128) NOT NULL COMMENT '模板名称，内置与自建模板共用同一个命名空间且唯一',
    source_type  VARCHAR(16)  NOT NULL COMMENT '本模板所读取的导出文件类型：CSV/XLSX/TXT/HTML',
    -- 不设 description 列：yaml 正文自带注释头，控制台是在文本域里直接编辑它的，
    -- 再来一个独立字段只会和运营人员正在读的内容对不上。
    profile_yaml MEDIUMTEXT   NOT NULL COMMENT '完整 yaml 正文，每次解析调用都原样转发给解析器',
    -- 内置模板只能被复制，不能被编辑或删除：下个版本会拿真实导出样本重新校准它，
    -- 那会悄悄覆盖掉原地做的修改。
    is_builtin   TINYINT      NOT NULL DEFAULT 0 COMMENT '1 内置模板，0 运营人员自建',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mapping_id (mapping_id),
    -- 名称是地址而不是标签：导入参数靠它解析出模板，两行同名会让解析结果变成任意挑一个。
    UNIQUE KEY uk_name (name),
    KEY idx_source_type (source_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='聊天导入映射模板，转发给解析器使用';
