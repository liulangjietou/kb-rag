package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Result of one retrieval call: the ordered nodes, the degradation markers that describe how the
 * pipeline actually ran, and the applied parameter summary the debug console displays.
 */
@Getter
@ToString
public final class SearchOutcome {

    /** Ordered result list. */
    private final List<RetrievalNodeView> nodes;

    /** Degradation markers, empty when the full pipeline ran. */
    private final List<String> degraded;

    /** Effective pipeline parameters. */
    private final AppliedInfo applied;

    public SearchOutcome(List<RetrievalNodeView> nodes, List<String> degraded, AppliedInfo applied) {
        this.nodes = nodes;
        this.degraded = degraded;
        this.applied = applied;
    }
}
