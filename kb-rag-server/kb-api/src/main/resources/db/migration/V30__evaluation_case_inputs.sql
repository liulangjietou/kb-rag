ALTER TABLE t_kb_eval_run
    ADD COLUMN case_inputs MEDIUMTEXT NULL COMMENT '提交时一致读取的用例输入快照，旧运行保持空值';
