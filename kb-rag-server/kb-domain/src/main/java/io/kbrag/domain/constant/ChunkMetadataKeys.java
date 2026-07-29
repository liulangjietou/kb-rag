package io.kbrag.domain.constant;

import java.util.Set;

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
     * Zero based position of the aggregation window inside its conversation.
     *
     * <p>Kept in MySQL only, like the session name: a window number is not a filter dimension, it is what
     * lets a debug page and a support conversation name the window a result came from.
     */
    public static final String WINDOW_SEQ = "window_seq";

    /**
     * Closed range of conversation message indices the window covers, a two element JSON array
     * {@code [first, last]}, zero based.
     *
     * <p>Written by the chat import for every window and by nothing else, which is exactly what makes it
     * the marker the retrieval side keys its near duplicate window merging on: a chunk without this key is
     * not an aggregation window and is left untouched.
     */
    public static final String MSG_SPAN = "msg_span";

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

    /**
     * One based page number the M14 {@code page} splitting strategy stamps on every chunk, stored as a
     * string so it rides the same engine projection and custom filter path the operator extracted keys
     * use.
     *
     * <p>Deliberately kept out of {@link #RESERVED}: it is a per document value the operator did not
     * declare a rule for, so it is mirrored under the {@code ext_} namespace like any other filterable
     * metadata rather than getting an engine field of its own.
     */
    public static final String PAGE_NO = "page_no";

    /**
     * Every key the platform itself writes, the M14 contract section 3.1.
     *
     * <p>The configuration gate rejects a metadata rule that reuses one of these, and the engine
     * projection treats every key outside this set as operator extracted: two purposes, one list, so
     * a key added above without being registered here would be shipped to the engines under the
     * {@code ext_} namespace instead of its own field.
     */
    public static final Set<String> RESERVED = Set.of(TAG_IDS, SESSION_ID, SENDER, MSG_TIME,
            SESSION_NAME, WINDOW_SEQ, MSG_SPAN, IMAGE_URLS, TITLE, SUMMARY, KEYWORDS);

    private ChunkMetadataKeys() {
    }
}
