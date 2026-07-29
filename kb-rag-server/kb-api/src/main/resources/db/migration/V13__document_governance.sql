-- M11：内容治理。文档上传的那一刻起就不可检索，只有治理侧点头之后才变得可检索：
-- 已发布、处于有效期内、且不在回收站里。存量数据一律取宽松的默认值，因此升级不改变任何行为。
SET NAMES utf8mb4;

ALTER TABLE t_kb_document
    ADD COLUMN publish_status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态：DRAFT 草稿 / PENDING_REVIEW 待审核 / PUBLISHED 已发布 / REJECTED 已驳回' AFTER process_status,
    ADD COLUMN review_note    VARCHAR(512)         DEFAULT NULL COMMENT '最近一次的驳回理由，审核通过时清空' AFTER publish_status,
    ADD COLUMN effective_at   DATETIME             DEFAULT NULL COMMENT '生效时间，从该时刻起可检索；为 null 表示不设下界' AFTER review_note,
    ADD COLUMN expires_at     DATETIME             DEFAULT NULL COMMENT '失效时间，在该时刻之前可检索；为 null 表示不设上界' AFTER effective_at,
    ADD COLUMN trashed        TINYINT      NOT NULL DEFAULT 0 COMMENT '文档在回收站中时置 1' AFTER expires_at,
    ADD COLUMN trashed_at     DATETIME             DEFAULT NULL COMMENT '进入回收站的时间，驱动彻底清除任务' AFTER trashed,
    ADD KEY idx_kb_publish (kb_id, publish_status),
    ADD KEY idx_kb_trashed (kb_id, trashed),
    ADD KEY idx_trashed_at (trashed_at);

ALTER TABLE t_kb_knowledge_base
    ADD COLUMN review_required TINYINT NOT NULL DEFAULT 0 COMMENT '置 1 时新文档的初始状态为 DRAFT 而不是 PUBLISHED' AFTER retrieval_config;
