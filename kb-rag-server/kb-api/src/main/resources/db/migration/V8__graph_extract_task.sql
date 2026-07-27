-- M7 GraphRAG.
--
-- The graph itself lives in Neo4j and is a derived store, so no table mirrors it: it is rebuildable from
-- t_kb_chunk exactly like the vector and full text indices are. The one thing MySQL has to remember is how
-- an extraction run went, and specifically how many chunks its output validation rejected - a count the
-- console shows next to a successful task, because "succeeded while silently dropping a third of the corpus"
-- is the failure mode this milestone has to make visible.
ALTER TABLE t_kb_task
    ADD COLUMN skipped_count INT DEFAULT NULL COMMENT 'chunks skipped by output validation, GRAPH_EXTRACT only';

ALTER TABLE t_kb_task
    MODIFY COLUMN task_type VARCHAR(32) NOT NULL COMMENT 'PARSE/INDEX/REBUILD/CLEANUP/GRAPH_EXTRACT';
