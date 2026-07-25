package io.kbrag.domain.constant;

/**
 * Keys of {@code t_kb_chunk.metadata} that are mirrored into the engine side filterable fields.
 *
 * <p>MySQL holds the whole metadata document, the engines only hold this fixed subset. Declaring the
 * bridge in one place is what keeps the indexing pipeline and the metadata filter from drifting: a
 * key that is written but not declared here would be silently unfilterable.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ChunkMetadataKeys {

    /** Document tag ids, a JSON array of strings. */
    public static final String TAG_IDS = "tag_ids";

    /** Chat session id. */
    public static final String SESSION_ID = "session_id";

    /** Chat message sender. */
    public static final String SENDER = "sender";

    /** Chat message timestamp in epoch milliseconds. */
    public static final String MSG_TIME = "msg_time";

    private ChunkMetadataKeys() {
    }
}
