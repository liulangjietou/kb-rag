package io.kbrag.domain.service;

import io.kbrag.domain.enums.RetrievalSource;

import java.util.Map;

/**
 * Helpers shared by the fusion strategies.
 *
 * @author owlzhangfq@gmail.com
 */
final class FusionSupport {

    private FusionSupport() {
    }

    /**
     * Picks the route that ranked a candidate best; ties fall to the route declared first in
     * {@link RetrievalSource}, which is the vector route.
     *
     * @param ranks one based rank per contributing route
     * @return best route, {@code null} when no route contributed
     */
    static RetrievalSource bestSource(Map<RetrievalSource, Integer> ranks) {
        RetrievalSource best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<RetrievalSource, Integer> entry : ranks.entrySet()) {
            if (entry.getValue() < bestRank) {
                bestRank = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }
}
