package io.kbrag.app.retrieval;

import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * One unit of the final result list.
 *
 * <p>With parent child splitting on this is a parent and the children that were recalled inside it;
 * without it, it is a single chunk wrapped in a group of one. Modelling both shapes the same way is
 * what lets the threshold, the truncation and the node builder stay unaware of the split mode.
 */
@Getter
@ToString
public final class RetrievalUnit {

    /** Chunk id of the returned unit: the parent id, or the chunk id itself when there is no parent. */
    private final String unitId;

    /** {@code true} when {@link #unitId} refers to a parent chunk. */
    private final boolean parent;

    /** Recalled members ordered by descending ordering score, never empty. */
    private final List<RetrievalCandidate> members;

    RetrievalUnit(String unitId, boolean parent, List<RetrievalCandidate> members) {
        this.unitId = unitId;
        this.parent = parent;
        this.members = members;
    }

    /**
     * Best member, the one whose score represents the unit.
     *
     * @return highest scoring member
     */
    public RetrievalCandidate best() {
        return members.get(0);
    }

    /**
     * Score of the unit.
     *
     * <p>Maximum rather than sum or mean: a parent is relevant because one passage inside it answers
     * the query, and summing would reward a long parent that mentions the topic everywhere without
     * answering anywhere, while averaging would punish a short precise hit surrounded by filler.
     *
     * @return highest member ordering score
     */
    public double score() {
        return best().orderingScore();
    }
}
