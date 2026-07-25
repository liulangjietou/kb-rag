package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.model.FusedChunk;
import lombok.Getter;
import lombok.ToString;

/**
 * One candidate travelling through the stages that follow fusion.
 *
 * <p>Carries the three things the later stages need together: the per route evidence produced by
 * fusion, the fact source row loaded from MySQL, and the rerank score once that stage has run. Keeping
 * them in one object is what lets the ordering score be defined in a single place instead of being
 * recomputed by every stage.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class RetrievalCandidate {

    /** Per route scores, ranks and the fusion score. */
    private final FusedChunk fused;

    /** Fact source row; for a two level knowledge base this is the child chunk. */
    private final Chunk chunk;

    /** Cross encoder score, {@code null} while the rerank stage has not run or degraded. */
    private Double rerankScore;

    public RetrievalCandidate(FusedChunk fused, Chunk chunk) {
        this.fused = fused;
        this.chunk = chunk;
    }

    /**
     * Chunk business id of this candidate.
     *
     * @return chunk id
     */
    public String chunkId() {
        return chunk.getChunkId();
    }

    /**
     * Attaches the cross encoder score.
     *
     * @param score relevance score
     */
    public void applyRerankScore(double score) {
        this.rerankScore = score;
    }

    /**
     * Score the final ordering is based on: the cross encoder score once it exists, the fusion score
     * otherwise. Defining it here is what makes a rerank degradation a pure fallback rather than a
     * different code path.
     *
     * @return ordering score
     */
    public double orderingScore() {
        return rerankScore != null ? rerankScore : fused.getFusedScore();
    }

    /**
     * Parent chunk business id, {@code null} for a single level knowledge base.
     *
     * @return parent chunk id
     */
    public String parentId() {
        return chunk.getParentId();
    }

    /**
     * Identity of the unit this candidate is returned as: the parent when there is one, otherwise
     * itself.
     *
     * @return merge key
     */
    public String unitKey() {
        String parentId = parentId();
        return parentId == null || parentId.isBlank() ? chunkId() : parentId;
    }
}
