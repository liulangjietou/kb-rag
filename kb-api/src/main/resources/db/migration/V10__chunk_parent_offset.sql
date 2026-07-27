-- Milestone M9 schema increment: where a child chunk sits inside its parent, requirement section 4.5.
--
-- Why a column and not a derivation. The retrieval side cuts the passage of a disabled child out of the
-- parent text before returning it, and recovering the position by searching the child text inside the
-- parent at query time would be both slower and ambiguous - overlapping children repeat text by design,
-- so a search could land on the wrong occurrence. The splitter already knows the position, because a
-- child is literally cut out of the parent, so it is written down once and read back cheaply.
--
-- Nullable on purpose, and null is the safe value. Every existing row gets NULL, which the retrieval side
-- reads as "this parent cannot be redacted precisely" and answers by returning the whole parent, exactly
-- as it did before this milestone. The annotation write paths clear the pair whenever the text moves.

ALTER TABLE t_kb_chunk
    ADD COLUMN parent_start_offset INT DEFAULT NULL COMMENT 'start offset of this child inside its parent content, null when unknown' AFTER parent_id,
    ADD COLUMN parent_end_offset   INT DEFAULT NULL COMMENT 'exclusive end offset of this child inside its parent content, null when unknown' AFTER parent_start_offset;
