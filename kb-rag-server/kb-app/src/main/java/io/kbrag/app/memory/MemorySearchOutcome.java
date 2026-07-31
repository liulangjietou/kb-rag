package io.kbrag.app.memory;

import io.kbrag.domain.entity.MemoryNode;

import java.util.List;

/**
 * What one SearchMemory call recalled, the M19 contract.
 *
 * @param nodes          recalled nodes with their scores, best first
 * @param profiles       the entity's profile views, returned alongside every search
 * @param rewrittenQuery query after rewrite, {@code null} when rewriting was off or degraded
 * @param intentRecalled {@code false} when intent recognition vetoed the recall
 * @author owlzhangfq@gmail.com
 */
public record MemorySearchOutcome(List<ScoredNode> nodes,
                                  List<MemoryProfileService.ProfileView> profiles,
                                  String rewrittenQuery, boolean intentRecalled) {

    /**
     * One recalled node.
     *
     * @param node  stored row hydrated from MySQL
     * @param score normalised relevance in {@code [0, 1]}; rerank score when rerank ran
     */
    public record ScoredNode(MemoryNode node, double score) {
    }
}
