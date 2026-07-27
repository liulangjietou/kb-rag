-- Milestone M6 schema increment: the index snapshot of an application version.
-- Two columns on the existing version table and no new table: the physical indices themselves are already
-- described by t_kb_index_registry, which the snapshot path registers a row in, so a second registry keyed by
-- application version would duplicate that description and could disagree with it.
-- V1-V6 are never edited after release, so every later change arrives as its own migration.

ALTER TABLE t_kb_app_version
    -- {kb_id: [document_version_id, ...]} frozen at release time, requirement section 4.7 "version visibility
    -- set". The mandatory engine side version filter of a released call takes its values from here instead of
    -- from the current active version pointer: an old snapshot only contains the versions of its own release,
    -- so filtering it by today's active versions would make a rollback recall nothing.
    -- NULL for a version that was never released, for one released before this milestone, and for one whose
    -- snapshot the retention pass already retired - all three fall back to the live alias.
    ADD COLUMN visible_version_ids JSON DEFAULT NULL
        COMMENT 'frozen document_version_id set per knowledge base, null falls back to the live alias'
        AFTER released_at,
    -- [{kb_id, engine, physical_index_name}], one element per knowledge base and per engine: a full mode
    -- deployment snapshots the BM25 index and the vector collection separately and a call has to address the
    -- right one per recall route. The physical name is stored rather than recomputed, so an embedding model
    -- switch after the release cannot repoint a historical version at an index it never held.
    ADD COLUMN index_snapshots JSON DEFAULT NULL
        COMMENT 'frozen physical indexes of the release, null falls back to the live alias'
        AFTER visible_version_ids;
