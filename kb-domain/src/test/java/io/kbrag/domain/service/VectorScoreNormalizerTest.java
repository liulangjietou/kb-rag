package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine consistency test of the vector score contract.
 *
 * <p>The same underlying cosine similarity must produce the same comparable score whether it arrives
 * as an Elasticsearch {@code (1+cos)/2} value or as a raw Milvus cosine; otherwise a score threshold
 * would silently mean two different things in lite and full mode.
 */
class VectorScoreNormalizerTest {

    private static final double TOLERANCE = 1e-9;

    /** Deviation budget of the engine consistency requirement. */
    private static final double ENGINE_TOLERANCE = 1e-3;

    @Test
    void shouldMapStandardCosineRangeOntoUnitInterval() {
        assertEquals(0.0d, VectorScoreNormalizer.fromMilvusScore(-1.0d), TOLERANCE);
        assertEquals(0.5d, VectorScoreNormalizer.fromMilvusScore(0.0d), TOLERANCE);
        assertEquals(1.0d, VectorScoreNormalizer.fromMilvusScore(1.0d), TOLERANCE);
    }

    @Test
    void shouldAgreeAcrossEnginesForTheSameSimilarity() {
        for (int step = -100; step <= 100; step++) {
            double cosine = step / 100.0d;
            double esScore = (1.0d + cosine) / 2.0d;
            double fromEs = VectorScoreNormalizer.fromElasticsearchScore(esScore);
            double fromMilvus = VectorScoreNormalizer.fromMilvusScore(cosine);
            assertTrue(Math.abs(fromEs - fromMilvus) < ENGINE_TOLERANCE,
                    "engines disagreed for cosine " + cosine + ": " + fromEs + " vs " + fromMilvus);
        }
    }

    @Test
    void shouldClampScoresOutsideTheExpectedDomain() {
        assertEquals(0.0d, VectorScoreNormalizer.fromMilvusScore(-2.0d), TOLERANCE);
        assertEquals(1.0d, VectorScoreNormalizer.fromMilvusScore(2.0d), TOLERANCE);
        assertEquals(1.0d, VectorScoreNormalizer.fromElasticsearchScore(1.5d), TOLERANCE);
    }
}
