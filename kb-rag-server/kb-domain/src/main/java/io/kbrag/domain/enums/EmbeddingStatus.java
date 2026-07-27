package io.kbrag.domain.enums;

/**
 * Embedding state of a chunk.
 *
 * @author owlzhangfq@gmail.com
 */
public enum EmbeddingStatus {

    /** Waiting for the embedding step. */
    PENDING,

    /** Vector produced and written to the vector index. */
    DONE,

    /** Embedding call failed after the configured retries. */
    FAILED,

    /** No embedding provider configured, the zero key mode skips this step. */
    SKIPPED
}
