-- 历史尝试时间不能推定为成功或内容变更，新增字段保持 NULL。
ALTER TABLE t_kb_web_source
    ADD COLUMN last_success_at DATETIME NULL COMMENT '最近完整抓取成功时间',
    ADD COLUMN last_content_change_at DATETIME NULL COMMENT '最近实际接入新内容时间';

ALTER TABLE t_kb_ext_source
    ADD COLUMN last_success_at DATETIME NULL COMMENT '最近完整同步成功时间',
    ADD COLUMN last_content_change_at DATETIME NULL COMMENT '最近实际接入新内容时间';
