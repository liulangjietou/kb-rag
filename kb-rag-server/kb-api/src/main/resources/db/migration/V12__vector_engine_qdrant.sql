-- 向量引擎由 Milvus 换为 Qdrant，同步两张索引台账的 engine 字段注释。
--
-- 只改注释，不动数据：存量 engine='milvus' 的行指向的是 Milvus 侧的物理集合，
-- 这些集合随 Milvus 一并下线后已不可达，简单改写成 'qdrant' 只会让台账指向不存在的
-- Qdrant collection。存量数据的正确处理是按 UPGRADING.md 重建索引，属于需要运维
-- 显式决策的动作，不在迁移里静默执行。

ALTER TABLE t_kb_chunk_index_sync
    MODIFY COLUMN engine VARCHAR(16) NOT NULL COMMENT 'es/qdrant';

ALTER TABLE t_kb_index_registry
    MODIFY COLUMN engine VARCHAR(16) NOT NULL COMMENT 'es/qdrant';
