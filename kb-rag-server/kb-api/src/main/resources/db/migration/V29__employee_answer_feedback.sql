ALTER TABLE t_kb_conversation_run
    ADD COLUMN feedback_verdict VARCHAR(16) NULL COMMENT '用户对本轮完整回答的最新评价';
ALTER TABLE t_kb_conversation_run
    ADD COLUMN feedback_note VARCHAR(512) NULL COMMENT '用户主动填写的问题说明';
ALTER TABLE t_kb_conversation_run
    ADD COLUMN feedback_updated_at DATETIME(3) NULL COMMENT '最近一次反馈更新时间';
