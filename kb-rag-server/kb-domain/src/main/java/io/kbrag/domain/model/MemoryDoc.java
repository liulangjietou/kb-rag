package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Search copy of one memory node as written to the memory index.
 *
 * <p>Carries only what recall needs - filters, the text and its vector. Everything else
 * (metadata, source, audit columns) stays in MySQL and is hydrated after the hit comes back, so
 * the index never becomes a second source of truth.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = "embedding")
public class MemoryDoc {

    /** Memory node business id, the document id of the index. */
    private final String nodeId;

    /** Library filter, the application isolation predicate. */
    private final String libraryId;

    /** Fragment rule that produced the node, an optional recall filter. */
    private final String ruleId;

    /** Memory entity filter, the in-library isolation predicate. */
    private final String userId;

    /** Remembered content, the BM25 field. */
    private final String content;

    /** Dense vector of the content, {@code null} when no embedding provider is configured. */
    private final float[] embedding;

    /** Expiry filter; {@code null} means the node never expires. */
    private final LocalDateTime expireAt;
}
