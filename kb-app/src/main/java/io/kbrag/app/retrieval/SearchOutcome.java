package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Result of one retrieval call: the ordered nodes plus the degradation markers that describe how the
 * pipeline actually ran.
 */
@Getter
@ToString
public final class SearchOutcome {

    /** Ordered result list. */
    private final List<RetrievalNodeView> nodes;

    /** Degradation markers, empty when the full pipeline ran. */
    private final List<String> degraded;

    public SearchOutcome(List<RetrievalNodeView> nodes, List<String> degraded) {
        this.nodes = nodes;
        this.degraded = degraded;
    }
}
