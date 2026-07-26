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

    /**
     * Display name of the chat session.
     *
     * <p>Not mirrored into an engine field: a display name is not a filter dimension, and the session id
     * already identifies the conversation. It is returned so a result card can be labelled.
     */
    public static final String SESSION_NAME = "session_name";

    /**
     * Object storage keys of the images this chunk derives from.
     *
     * <p>Kept in MySQL only. The keys are turned into time limited pre signed URLs at retrieval time, so
     * indexing them would store a value that is useless to a search and stale by the time it is read.
     */
    public static final String IMAGE_URLS = "image_urls";

    /** Chunk title produced alongside the LLM semantic split, requirement section 4.3. */
    public static final String TITLE = "title";

    /** Chunk summary produced alongside the LLM semantic split. */
    public static final String SUMMARY = "summary";

    /** Chunk keywords produced alongside the LLM semantic split, a JSON array of strings. */
    public static final String KEYWORDS = "keywords";

    private ChunkMetadataKeys() {
    }
}
