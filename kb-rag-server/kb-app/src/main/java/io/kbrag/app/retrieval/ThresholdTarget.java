package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.ScoreType;

/**
 * Score an absolute threshold is allowed to act on.
 *
 * <p>Only two scores in the pipeline mean the same thing across queries: the cross encoder relevance
 * and the normalised cosine similarity. Everything else is either unbounded (raw BM25) or relative to
 * the candidate set of one query (both fusion scores), so a fixed number compared against them would
 * silently mean something different on every search.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ThresholdTarget {

    /** Cross encoder relevance, the preferred target whenever the rerank stage ran. */
    RERANK(ScoreType.RERANK),

    /** Standard cosine similarity mapped to {@code [0,1]}, used when rerank is off or degraded. */
    COSINE(ScoreType.COSINE),

    /** No comparable score exists, the threshold cannot be honoured. */
    NONE(null);

    private final ScoreType scoreType;

    ThresholdTarget(ScoreType scoreType) {
        this.scoreType = scoreType;
    }

    /**
     * Score type reported for nodes filtered by this target.
     *
     * @return score type, {@code null} for {@link #NONE}
     */
    public ScoreType scoreType() {
        return scoreType;
    }

    /**
     * Literal exposed through the {@code applied} block of the response.
     *
     * @return API side value
     */
    public String code() {
        return scoreType == null ? "none" : scoreType.code();
    }
}
