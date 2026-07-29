-- M7 GraphRAG。
--
-- 图谱本身存在 Neo4j 里，属于派生存储，因此 MySQL 不做任何镜像表：它和向量、全文索引一样，
-- 随时可以从 t_kb_chunk 重建。MySQL 唯一需要记住的是某次抽取运行的情况，特别是它的输出校验
-- 拒掉了多少个分片——这个计数会展示在「成功」的任务旁边，因为「任务成功、却悄悄丢掉三分之一
-- 语料」正是本里程碑必须暴露出来的失败模式。
SET NAMES utf8mb4;

ALTER TABLE t_kb_task
    ADD COLUMN skipped_count INT DEFAULT NULL COMMENT '被输出校验跳过的分片数，仅 GRAPH_EXTRACT 任务使用';

ALTER TABLE t_kb_task
    MODIFY COLUMN task_type VARCHAR(32) NOT NULL COMMENT '任务类型：PARSE/INDEX/REBUILD/CLEANUP/GRAPH_EXTRACT';
