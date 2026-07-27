package io.kbrag.domain.service;

/**
 * Converts engine specific vector scores into one comparable score.
 *
 * <p>Why this exists. Elasticsearch reports {@code (1 + cos) / 2} for a cosine {@code dense_vector}
 * while Qdrant reports the raw cosine in {@code [-1,1]}. Leaving the conversion to each adapter would
 * make a score threshold of 0.5 mean two different things depending on the deployment mode, which
 * would break the promise that lite and full behave identically.
 *
 * <p>The contract is therefore stated once, here: every engine score is first restored to the standard
 * cosine domain {@code [-1,1]}, then linearly mapped to {@code [0,1]}. Both adapters delegate to this
 * class and one test asserts they agree for the same underlying similarity.
 *
 * @author owlzhangfq@gmail.com
 */
public final class VectorScoreNormalizer {

    private VectorScoreNormalizer() {
    }

    /**
     * Normalises an Elasticsearch cosine score.
     *
     * @param esScore raw {@code _score} of a cosine dense_vector query, which equals {@code (1+cos)/2}
     * @return standard cosine linearly mapped to {@code [0,1]}
     */
    public static double fromElasticsearchScore(double esScore) {
        double standardCosine = esScore * 2.0d - 1.0d;
        return fromStandardCosine(standardCosine);
    }

    /**
     * Normalises a Qdrant cosine score.
     *
     * @param qdrantScore raw cosine similarity in {@code [-1,1]}
     * @return standard cosine linearly mapped to {@code [0,1]}
     */
    public static double fromQdrantScore(double qdrantScore) {
        return fromStandardCosine(qdrantScore);
    }

    /**
     * Maps a standard cosine similarity to the comparable score every threshold acts on.
     *
     * @param standardCosine cosine similarity in {@code [-1,1]}
     * @return value in {@code [0,1]}
     */
    private static double fromStandardCosine(double standardCosine) {
        double normalized = (standardCosine + 1.0d) / 2.0d;
        return Math.max(0.0d, Math.min(1.0d, normalized));
    }
}
