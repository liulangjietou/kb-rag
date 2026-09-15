-- 独立删除评测资源前按业务 ID 检查引用，避免扫描整张质量问题表。
ALTER TABLE t_kb_quality_issue
    ADD KEY idx_quality_issue_dataset (dataset_id, deleted),
    ADD KEY idx_quality_issue_case (case_id, deleted);
