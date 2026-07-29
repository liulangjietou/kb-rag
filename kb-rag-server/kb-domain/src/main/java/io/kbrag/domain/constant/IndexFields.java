package io.kbrag.domain.constant;

/**
 * Engine side field names, declared once and shared by every store implementation.
 *
 * <p>This is the complete set of filterable fields. Adding a dimension here is a schema change and
 * has to go through the rebuild plus alias switch migration path, which is why the names are not
 * inlined at any call site.
 *
 * @author owlzhangfq@gmail.com
 */
public final class IndexFields {

    /** Primary key, equal to the chunk business id. */
    public static final String CHUNK_ID = "chunk_id";

    /** Knowledge base scope. */
    public static final String KB_ID = "kb_id";

    /** Owning document. */
    public static final String DOC_ID = "doc_id";

    /** Owning document version, the mandatory version isolation filter. */
    public static final String DOCUMENT_VERSION_ID = "document_version_id";

    /** Parent chunk id. */
    public static final String PARENT_ID = "parent_id";

    /** Chunk type, {@code text}, {@code image} or {@code chat_log}. */
    public static final String CHUNK_TYPE = "chunk_type";

    /** Retrieval switch. */
    public static final String ENABLED = "enabled";

    /** Document tag ids. */
    public static final String TAG_IDS = "tag_ids";

    /** Chat session id. */
    public static final String SESSION_ID = "session_id";

    /** Chat message sender. */
    public static final String SENDER = "sender";

    /** Chat message timestamp in epoch milliseconds. */
    public static final String MSG_TIME = "msg_time";

    /** Order inside the document version. */
    public static final String CHUNK_SEQ = "chunk_seq";

    /** Indexed chunk text. */
    public static final String CONTENT = "content";

    /** Embedding vector. */
    public static final String VECTOR = "vector";

    /**
     * Namespace prefix of the operator extracted metadata fields, the M14 contract section 3.2.
     *
     * <p>The prefix exists only engine side: MySQL stores the raw rule key, and prefixing on the way
     * into the engines is what keeps an operator chosen key from ever colliding with a column of the
     * fixed mapping above.
     */
    public static final String EXT_PREFIX = "ext_";

    private IndexFields() {
    }
}
