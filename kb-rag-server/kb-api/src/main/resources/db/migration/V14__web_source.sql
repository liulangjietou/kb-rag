-- M12：URL 导入与增量同步。一个已注册的网页源，是 URL 与其抓取所产出文档之间的桥梁：
-- 抓取本身汇入普通的上传链路，因此这张表只记录绑定关系、同步开关和最近一次抓取的结果。
SET NAMES utf8mb4;

CREATE TABLE t_kb_web_source
(
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    source_id         VARCHAR(64)   NOT NULL COMMENT '控制台对外暴露的业务标识',
    kb_id             VARCHAR(64)   NOT NULL COMMENT '抓取到的页面所落入的知识库',
    url               VARCHAR(2048) NOT NULL COMMENT '已注册的页面地址，http 或 https',
    -- 等值比较用的键：VARCHAR(2048) 撑不起唯一索引，它的摘要可以。
    url_hash          CHAR(64)      NOT NULL COMMENT 'url 的 SHA-256，去重与查找键',
    doc_id            VARCHAR(64)            DEFAULT NULL COMMENT '抓取内容所喂养的文档，首次成功前为 null',
    file_name         VARCHAR(255)           DEFAULT NULL COMMENT '派生出的稳定文件名，上传链路看到的就是它',
    sync_enabled      TINYINT       NOT NULL DEFAULT 1 COMMENT '置 1 时该源纳入定时同步任务',
    last_content_hash CHAR(64)               DEFAULT NULL COMMENT '最近一次抓取正文的 SHA-256，用于判断内容是否未变',
    last_fetch_at     DATETIME               DEFAULT NULL COMMENT '最近一次同步尝试的执行时间',
    last_fetch_status VARCHAR(16)            DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS/UNCHANGED/SKIPPED/FAILED',
    last_error        VARCHAR(512)           DEFAULT NULL COMMENT '最近一次同步失败或被跳过的原因，成功时为 null',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_id (source_id),
    -- 同一个知识库下一个 URL 只能注册一次；其他知识库可以注册同一个 URL。
    UNIQUE KEY uk_kb_url (kb_id, url_hash),
    KEY idx_kb_id (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='已注册的网页源，经 URL 导入喂养文档';
