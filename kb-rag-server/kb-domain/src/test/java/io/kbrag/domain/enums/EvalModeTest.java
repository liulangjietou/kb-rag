package io.kbrag.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four configuration matrix modes' mapping onto the existing retrieval parameters,
 * requirement section 4.6: which route each one forces off and whether rerank is requested.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalModeTest {

    @Test
    void bm25OnlyShouldForceTheVectorRouteOffAndKeepRerankOff() {
        assertTrue(EvalMode.BM25_ONLY.bm25RouteEnabled());
        assertFalse(EvalMode.BM25_ONLY.vectorRouteEnabled());
        assertFalse(EvalMode.BM25_ONLY.requiresVector());
        assertFalse(EvalMode.BM25_ONLY.rerankRequested());
    }

    @Test
    void vectorOnlyShouldForceTheBm25RouteOffAndRequireAnEmbeddingProvider() {
        assertFalse(EvalMode.VECTOR_ONLY.bm25RouteEnabled());
        assertTrue(EvalMode.VECTOR_ONLY.vectorRouteEnabled());
        assertTrue(EvalMode.VECTOR_ONLY.requiresVector());
        assertFalse(EvalMode.VECTOR_ONLY.rerankRequested());
    }

    @Test
    void hybridShouldRunBothRoutesFusedWithRerankOff() {
        assertTrue(EvalMode.HYBRID.bm25RouteEnabled());
        assertTrue(EvalMode.HYBRID.vectorRouteEnabled());
        assertTrue(EvalMode.HYBRID.requiresVector());
        assertFalse(EvalMode.HYBRID.rerankRequested());
    }

    @Test
    void hybridRerankShouldRunBothRoutesFusedWithRerankOn() {
        assertTrue(EvalMode.HYBRID_RERANK.bm25RouteEnabled());
        assertTrue(EvalMode.HYBRID_RERANK.vectorRouteEnabled());
        assertTrue(EvalMode.HYBRID_RERANK.requiresVector());
        assertTrue(EvalMode.HYBRID_RERANK.rerankRequested());
    }

    @Test
    void shouldResolveCaseInsensitively() {
        assertTrue(EvalMode.from("hybrid_rerank") == EvalMode.HYBRID_RERANK);
    }

    @Test
    void shouldRejectAnUnknownLiteral() {
        assertThrows(IllegalArgumentException.class, () -> EvalMode.from("nope"));
    }
}
