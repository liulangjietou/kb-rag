package io.kbrag.common.constant;

/**
 * Cross module constants, kept here so no magic value is inlined at a call site.
 *
 * @author owlzhangfq@gmail.com
 */
public final class KbConstants {

    /** Bearer scheme prefix of the {@code Authorization} header. */
    public static final String BEARER_PREFIX = "Bearer ";

    /** Standard authorization header name. */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Business id prefix of a knowledge base. */
    public static final String KB_ID_PREFIX = "kb";

    /** Business id prefix of a document. */
    public static final String DOC_ID_PREFIX = "doc";

    /** Business id prefix of a document version. */
    public static final String VERSION_ID_PREFIX = "dv";

    /** Business id prefix of a chunk. */
    public static final String CHUNK_ID_PREFIX = "ck";

    /** Business id prefix of an asynchronous task. */
    public static final String TASK_ID_PREFIX = "task";

    /** Business id prefix of an image asset. */
    public static final String IMAGE_ASSET_ID_PREFIX = "img";

    /** Business id prefix of a chat import upload token. */
    public static final String UPLOAD_TOKEN_PREFIX = "upt";

    /**
     * Source channel of a chat import, first element of the logical document identity.
     *
     * <p>The identity is {@code chat:{sessionId}}: a conversation is the same logical document across
     * two exports of the same channel, which is what turns a re-import into a new version instead of a
     * duplicate document.
     */
    public static final String SOURCE_CHANNEL_CHAT = "chat";

    /** Separator between a business id prefix and its random part. */
    public static final String ID_SEPARATOR = "_";

    /** Initial document version number, see requirement section 4.1. */
    public static final String INITIAL_VERSION = "1.0";

    /** Snapshot segment of a physical index name, fixed for M1. */
    public static final String SNAPSHOT_SEGMENT_V1 = "v1";

    /** Embedding version segment used when no embedding provider is configured. */
    public static final String EMBEDDING_SEGMENT_NONE = "none";

    /** Embedding version segment of the full mode Elasticsearch index, which only serves BM25. */
    public static final String EMBEDDING_SEGMENT_BM25 = "bm25";

    /** Index schema version, bumped whenever the filterable field set changes. */
    public static final String INDEX_SCHEMA_VERSION = "1";

    private KbConstants() {
    }
}
