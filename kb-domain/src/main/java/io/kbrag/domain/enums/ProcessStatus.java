package io.kbrag.domain.enums;

/**
 * Single valued processing state of a document, orthogonal to {@code config_stale}.
 *
 * <p>Transitions: UPLOADED -&gt; PARSING -&gt; (PARSE_FAILED | PARSED) -&gt; [PENDING_CONFIRM] -&gt; INDEXING
 * -&gt; (INDEXED | INDEX_FAILED). {@code PENDING_CONFIRM} only appears when the knowledge base asks for
 * a parse preview, so a knowledge base that does not use the feature keeps the original transitions.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ProcessStatus {

    /** File stored in object storage, pipeline not started yet. */
    UPLOADED,

    /** Parser service invocation in progress. */
    PARSING,

    /** Parser service rejected the file or failed. */
    PARSE_FAILED,

    /** Parsed markdown is available. */
    PARSED,

    /**
     * Parsing and cleaning are done and the pipeline is waiting for a human decision.
     *
     * <p>Reached only when {@code index_config.parse_preview_required} is on. Nothing has been split
     * or indexed yet, so a document left in this state is invisible to retrieval.
     */
    PENDING_CONFIRM,

    /** Splitting, persisting and index writing in progress. */
    INDEXING,

    /** Chunks are searchable through the knowledge base aliases. */
    INDEXED,

    /** Splitting or index writing failed. */
    INDEX_FAILED
}
