package io.kbrag.app.retrieval;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.GraphChunkRelevance;
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

    /**
     * Raw BM25 route score of this candidate, {@code null} when only the vector route recalled it, the
     * M14 contract section 5.
     *
     * <p>Captured at construction from the fusion evidence so the hybrid rerank mode can blend it in
     * without re-reading the route map: a pure vector hit has no keyword score and counts as zero in
     * the blend rather than being dropped.
     */
    private final Double bm25Score;

    /**
     * Blended ordering score of the {@code hybrid} rerank mode, {@code null} unless that mode ran, the
     * M14 contract section 5. Kept apart from {@link #rerankScore} because the threshold must still act
     * on the pure semantic score while the ordering follows this blend.
     */
    private Double hybridScore;

    /**
     * How the graph route reached this chunk, {@code null} when it did not, requirement section 4.9.
     *
     * <p>Kept next to the fusion evidence rather than folded into it: the fusion score is one number per
     * route, while the hop count and the matched entity names are what actually explain the graph route
     * to an operator, and they have no place in a score map.
     */
    private GraphChunkRelevance graphEvidence;

    public RetrievalCandidate(FusedChunk fused, Chunk chunk) {
        this.fused = fused;
        this.chunk = chunk;
        this.bm25Score = fused == null ? null : fused.routeScore(RetrievalSource.BM25);
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
     * Attaches the blended ordering score computed by the {@code hybrid} rerank mode.
     *
     * @param score blend of the semantic relevance and the normalised BM25 score
     */
    public void applyHybridScore(double score) {
        this.hybridScore = score;
    }

    /**
     * Attaches the graph route evidence of this chunk.
     *
     * @param evidence relevance detail, {@code null} when the graph route did not reach this chunk
     */
    public void applyGraphEvidence(GraphChunkRelevance evidence) {
        this.graphEvidence = evidence;
    }

    /**
     * Score the final ordering is based on: the hybrid blend once the hybrid mode produced it, the cross
     * encoder score once it exists, the fusion score otherwise. Defining it here is what makes a rerank
     * degradation and the hybrid blend pure ordering choices rather than different code paths.
     *
     * @return ordering score
     */
    public double orderingScore() {
        if (hybridScore != null) {
            return hybridScore;
        }
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
