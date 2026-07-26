package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.DegradedReason;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Which knowledge bases one call is searched in, and whether the routing stage had to fall back.
 *
 * <p>The two are reported together because a fallback is not an error path: the bases to search are
 * always populated, and the marker only tells the caller that the list is the whole linked set rather
 * than a model's selection.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class RoutingOutcome {

    /** Knowledge base ids to search, in declaration order, never empty. */
    private final List<String> kbIds;

    /** Degradation marker, {@code null} when routing ran as configured or was legitimately off. */
    private final String degradedReason;

    private RoutingOutcome(List<String> kbIds, String degradedReason) {
        this.kbIds = kbIds;
        this.degradedReason = degradedReason;
    }

    /**
     * Routing did not run, which is the configured behaviour rather than a degradation.
     *
     * @param kbIds every linked knowledge base
     * @return outcome without a marker
     */
    public static RoutingOutcome skipped(List<String> kbIds) {
        return new RoutingOutcome(kbIds, null);
    }

    /**
     * A model selected a subset of the linked bases.
     *
     * @param kbIds selected knowledge base ids, already intersected with the white list
     * @return outcome without a marker
     */
    public static RoutingOutcome routed(List<String> kbIds) {
        return new RoutingOutcome(kbIds, null);
    }

    /**
     * Routing was asked for and could not be honoured, so every linked base is searched.
     *
     * @param kbIds every linked knowledge base
     * @return outcome carrying {@link DegradedReason#ROUTE_FALLBACK_ALL}
     */
    public static RoutingOutcome fallbackAll(List<String> kbIds) {
        return new RoutingOutcome(kbIds, DegradedReason.ROUTE_FALLBACK_ALL.code());
    }
}
