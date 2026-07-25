package io.kbrag.app.retrieval;

import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.MetadataFilter;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * One retrieval request as the caller expressed it.
 *
 * <p>Every tuning field is nullable and stays nullable all the way down: {@code null} is what
 * distinguishes "the caller did not care" from "the caller asked for this value", and only the first
 * one may fall through to the knowledge base defaults. Resolving the two into one value happens once,
 * inside the retrieval service.
 */
@Getter
@Builder
@ToString(exclude = {"query", "messages"})
public class RetrievalCommand {

    /** User query, mandatory. */
    private final String query;

    /** Candidates recalled per route. */
    private final Integer recallTopK;

    /** Number of returned units. */
    private final Integer topN;

    /** Absolute score threshold. */
    private final Double scoreThreshold;

    /** Fusion strategy literal. */
    private final String fusionMode;

    /** Vector weight of the weighted strategy. */
    private final Double wVec;

    /** Damping constant of the reciprocal rank strategy. */
    private final Integer rrfK;

    /** Rerank switch. */
    private final Boolean rerankEnabled;

    /** Query rewrite switch. */
    private final Boolean rewriteEnabled;

    /** Conversation used to resolve references during the rewrite. */
    private final List<ChatMessage> messages;

    /** Caller supplied narrowing predicate. */
    private final MetadataFilter metadataFilter;
}
