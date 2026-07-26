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
 *
 * @author owlzhangfq@gmail.com
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

    /**
     * Knowledge base routing switch, requirement section 4.9.
     *
     * <p>Only an application version snapshot ever sets it: routing selects among the bases the
     * application declared, so a request that could switch it on would be deciding on a configuration it
     * cannot see. The management console's single base debug endpoint leaves it {@code null}.
     */
    private final Boolean routingEnabled;

    /** Operator wording of the routing instruction, blank or {@code null} keeps the built in one. */
    private final String routingPrompt;

    /** Conversation used to resolve references during the rewrite. */
    private final List<ChatMessage> messages;

    /** Caller supplied narrowing predicate. */
    private final MetadataFilter metadataFilter;

    /**
     * Forces the BM25 route off regardless of what a query would otherwise recall.
     *
     * <p>Not reachable from the public search API - only the evaluation runner sets it, to realise
     * {@code VECTOR_ONLY} in a configuration matrix (requirement section 4.6) even once an embedding
     * provider is configured, when the BM25 route would otherwise still run.
     */
    private final Boolean bm25RouteEnabled;

    /**
     * Forces the vector route off regardless of embedding provider availability.
     *
     * <p>Same rationale as {@link #bm25RouteEnabled}, the other direction: it is what {@code BM25_ONLY}
     * needs once an embedding provider is configured, so the two single route evaluation modes stay
     * single route after a zero key deployment gets its embedding model.
     */
    private final Boolean vectorRouteEnabled;
}
