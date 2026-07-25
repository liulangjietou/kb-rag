package io.kbrag.domain.enums;

/**
 * Single valued processing state of a document, orthogonal to {@code config_stale}.
 *
 * <p>Transitions: UPLOADED -&gt; PARSING -&gt; (PARSE_FAILED | PARSED) -&gt; INDEXING -&gt;
 * (INDEXED | INDEX_FAILED). The {@code PENDING_CONFIRM} state of the requirement document belongs
 * to the parse preview switch which is out of the M1 scope.
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

    /** Splitting, persisting and index writing in progress. */
    INDEXING,

    /** Chunks are searchable through the knowledge base aliases. */
    INDEXED,

    /** Splitting or index writing failed. */
    INDEX_FAILED
}
