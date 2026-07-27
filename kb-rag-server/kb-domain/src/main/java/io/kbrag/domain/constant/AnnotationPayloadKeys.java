package io.kbrag.domain.constant;

/**
 * Keys of the {@code t_kb_annotation.payload} JSON document.
 *
 * <p>The payload is schemaless on purpose - the four operation kinds carry genuinely different detail -
 * but the keys are declared here so the review list, the console and the writers all name them the same
 * way. A key written under one spelling and read under another would surface as an empty review entry.
 *
 * @author owlzhangfq@gmail.com
 */
public final class AnnotationPayloadKeys {

    /** Retrieval switch value a toggle wrote. */
    public static final String ENABLED = "enabled";

    /** Children whose retrieval switch followed the one of their parent. */
    public static final String CASCADED_CHILD_IDS = "cascaded_child_ids";

    /** Chunk text before an edit, truncated. */
    public static final String BEFORE_EXCERPT = "before_excerpt";

    /** Chunk text after an edit, truncated. */
    public static final String AFTER_EXCERPT = "after_excerpt";

    /** Normalised digest of the chunk text before an edit. */
    public static final String BEFORE_HASH = "before_hash";

    /** Chunks a merge consumed, in the order their text was concatenated. */
    public static final String SOURCE_CHUNK_IDS = "source_chunk_ids";

    /** Chunk a merge or a split produced; a list for a split. */
    public static final String RESULT_CHUNK_IDS = "result_chunk_ids";

    /** Character offsets a split cut the text at. */
    public static final String SPLIT_OFFSETS = "split_offsets";

    /** Text of the operation target, truncated. */
    public static final String EXCERPT = "excerpt";

    /** Version an inherited disable annotation was copied from. */
    public static final String INHERITED_FROM_VERSION_ID = "inherited_from_version_id";

    /** Children re-cut because their parent was merged or split. */
    public static final String RECUT_CHILD_COUNT = "recut_child_count";

    private AnnotationPayloadKeys() {
    }
}
