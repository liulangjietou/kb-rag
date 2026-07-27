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

    /** Business id prefix of a chunk annotation. */
    public static final String ANNOTATION_ID_PREFIX = "an";

    /** Business id prefix of an evaluation data set. */
    public static final String EVAL_DATASET_ID_PREFIX = "evds";

    /** Business id prefix of an evaluation case. */
    public static final String EVAL_CASE_ID_PREFIX = "evc";

    /** Business id prefix of an evaluation run. */
    public static final String EVAL_RUN_ID_PREFIX = "evr";

    /** Business id prefix of an evaluation result row. */
    public static final String EVAL_RESULT_ID_PREFIX = "evre";

    /** Business id prefix of an application. */
    public static final String APP_ID_PREFIX = "app";

    /** Business id prefix of an application version. */
    public static final String APP_VERSION_ID_PREFIX = "av";

    /** Business id prefix of an API key row, never the key material itself. */
    public static final String API_KEY_ID_PREFIX = "ak";

    /** Business id prefix of an outbound call audit row. */
    public static final String API_AUDIT_LOG_ID_PREFIX = "aud";

    /** Business id prefix of a chat import mapping profile row. */
    public static final String SOURCE_MAPPING_ID_PREFIX = "smp";

    /**
     * Fixed prefix of every open API key plaintext, requirement section 4.8 "kb-sk-{prefix}{random}".
     *
     * <p>Kept recognisable on purpose: a leaked string can be identified as a credential of this system
     * by pattern alone, which is what lets secret scanners revoke it.
     */
    public static final String API_KEY_PLAINTEXT_PREFIX = "kb-sk-";

    /**
     * Operator recorded on every annotation.
     *
     * <p>Fixed for now: the console has exactly one administrator account, so a per user value would
     * be a column that always holds the same string while pretending to be an audit dimension.
     */
    public static final String ANNOTATION_OPERATOR_ADMIN = "admin";

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

    /** Snapshot segment of the live physical index, the one every alias points at. */
    public static final String SNAPSHOT_SEGMENT_V1 = "v1";

    /**
     * Prefix of the snapshot segment of a release snapshot index, followed by the knowledge base level
     * sequence number ({@code s1}, {@code s2}, ...).
     *
     * <p>Also the marker that tells a snapshot registry row apart from a live one: the live segment is
     * {@code v1}, so a prefix match on this letter is an exact predicate rather than a heuristic.
     */
    public static final String SNAPSHOT_SEGMENT_PREFIX = "s";

    /** Embedding version segment used when no embedding provider is configured. */
    public static final String EMBEDDING_SEGMENT_NONE = "none";

    /** Embedding version segment of the full mode Elasticsearch index, which only serves BM25. */
    public static final String EMBEDDING_SEGMENT_BM25 = "bm25";

    /** Index schema version, bumped whenever the filterable field set changes. */
    public static final String INDEX_SCHEMA_VERSION = "1";

    private KbConstants() {
    }
}
