package io.kbrag.app.retrieval;

import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.MetadataFilter;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

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

    /**
     * Images attached to the query, base64 encoded, the M14 contract section 7.
     *
     * <p>Left for the retrieval service to dispatch: when the searched base can embed them they steer the
     * multimodal route directly (image to image), otherwise they are transcribed into the query by the
     * vision fallback. Either way the count and size are bounded by the one image validation gate.
     */
    private final List<String> images;

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

    /** Rerank ordering mode literal, {@code null} keeps the resolved default, the M14 contract section 5. */
    private final String rerankMode;

    /** Semantic weight of the {@code hybrid} rerank mode, {@code null} keeps the resolved default. */
    private final Double rerankWSemantic;

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

    /**
     * Physical indices to search per knowledge base id, replacing the live aliases, requirement section 4.7.
     *
     * <p>Set only when a released application version serves the call out of its frozen index snapshot. Unset
     * everywhere else - a beta call against a test version, a chat preview, the console debug page, an
     * evaluation run - because all of those are meant to observe the corpus as it is <em>now</em>, which is the
     * whole reason the console can prove a snapshot isolates a release.
     */
    private final Map<String, RetrievalIndexOverride> indexOverride;

    /**
     * Version visibility set to filter with per knowledge base id, replacing the current active versions.
     *
     * <p>Travels with {@link #indexOverride} and is never used without it: a frozen set names the document
     * versions a snapshot contains, so applying it to the live index would filter out everything indexed since
     * the release, and applying today's active versions to a snapshot would match nothing at all. The two are a
     * pair, and the retrieval service resolves them as one.
     */
    private final Map<String, List<String>> visibleVersionIdsOverride;
}
