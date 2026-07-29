-- M10：检索质量闭环。反馈不再只是一行日志，而是可被管理、并能转化成评测用例的记录；
-- 同时每一次线上检索都会留下一行洞察数据，好让控制台回答「大家在搜什么、而语料答不上来」。
SET NAMES utf8mb4;

CREATE TABLE t_kb_retrieval_feedback
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    feedback_id       VARCHAR(64)  NOT NULL COMMENT '控制台对外暴露的业务标识',
    kb_id             VARCHAR(64)  NOT NULL COMMENT '本次查询所命中的知识库',
    -- 与洞察表的脱敏摘要不同，这里刻意存原文：把一条反馈转成评测用例时要重放一模一样的查询，
    -- 存脱敏副本会造出一个从未真正跑过的用例。
    query             TEXT         NOT NULL COMMENT '调试页执行的查询原文，转化用例时按原文重放',
    chunk_id          VARCHAR(64)  NOT NULL COMMENT '本次评价所针对的分片',
    doc_id            VARCHAR(64)           DEFAULT NULL COMMENT '所属文档，由服务端解析；分片已被删除时为 null',
    verdict           VARCHAR(16)  NOT NULL COMMENT '评价结论：GOOD 好评 / BAD 差评',
    status            VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT '处理状态：NEW 待处理 / CONVERTED 已转用例 / DISMISSED 已忽略',
    converted_case_id VARCHAR(64)           DEFAULT NULL COMMENT '由本行转化而来的评测用例 id，未转化时为 null',
    note              VARCHAR(512)          DEFAULT NULL COMMENT '运营人员的自由文本备注',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_id (feedback_id),
    KEY idx_kb_id (kb_id),
    KEY idx_status (status),
    -- 控制台列出单个知识库的待处理反馈，条件正好就是这两个字段。
    KEY idx_kb_status (kb_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='对调试结果的好评/差评，可转化为评测用例';

CREATE TABLE t_kb_search_insight
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    insight_id   VARCHAR(64) NOT NULL COMMENT '控制台对外暴露的业务标识',
    kb_id        VARCHAR(64) NOT NULL COMMENT '本次检索所命中的知识库',
    source       VARCHAR(16) NOT NULL COMMENT '调用入口：CONSOLE 控制台 / OPEN_API 开放接口',
    -- 与 t_kb_api_audit_log 完全一致的纪律：先按 4.2 节规则脱敏再截断。
    -- 一张要保留 90 天的分析表，绝不能变成用户输入内容的未脱敏副本。
    query_digest VARCHAR(200)         DEFAULT NULL COMMENT '脱敏并截断后的查询文本，绝不存原文',
    query_hash   CHAR(64)    NOT NULL COMMENT '归一化查询的 SHA-256，未命中报表的分组键',
    result_count INT         NOT NULL DEFAULT 0 COMMENT '本次调用返回的结果条数',
    top_score    DOUBLE               DEFAULT NULL COMMENT '首条结果的得分，零命中时为 null',
    zero_hit     TINYINT     NOT NULL DEFAULT 0 COMMENT '由 result_count = 0 派生，报表实际扫描的就是这一列',
    degraded     JSON                 DEFAULT NULL COMMENT '本次调用的降级标记',
    request_id   VARCHAR(64)          DEFAULT NULL COMMENT '链路追踪 id，把本行与日志、审计关联起来',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_insight_id (insight_id),
    KEY idx_kb_id (kb_id),
    KEY idx_query_hash (query_hash),
    -- 单个知识库在某个时间窗内的零命中报表，条件正好就是这三个字段。
    KEY idx_kb_zero_created (kb_id, zero_hit, created_at),
    -- 保留期清理任务扫描已过期的行。
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='线上检索逐次留痕，超过保留期后删除';
