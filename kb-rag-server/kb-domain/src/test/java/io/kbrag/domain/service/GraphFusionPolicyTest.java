package io.kbrag.domain.service;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.model.KbRetrievalConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the single gate of "a knowledge base with the graph route on fuses with reciprocal ranks",
 * requirement section 4.4.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphFusionPolicyTest {

    private final GraphFusionPolicy policy = new GraphFusionPolicy();

    @Test
    void shouldRejectTheGraphRouteCombinedWithWeightedFusion() {
        BizException exception = assertThrows(BizException.class,
                () -> policy.requireCompatible(configOf(true, FusionMode.WEIGHTED.code())));

        assertEquals(ErrorCode.INVALID_PARAM, exception.getErrorCode());
    }

    @Test
    void shouldRejectRegardlessOfTheLiteralCasing() {
        assertThrows(BizException.class, () -> policy.requireCompatible(configOf(true, "WEIGHTED")));
    }

    @Test
    void shouldAcceptTheGraphRouteWithReciprocalRankFusion() {
        assertDoesNotThrow(() -> policy.requireCompatible(configOf(true, FusionMode.RRF.code())));
    }

    @Test
    void shouldAcceptTheGraphRouteWithAnUnsetFusionModeBecauseTheDefaultIsReciprocalRank() {
        assertDoesNotThrow(() -> policy.requireCompatible(configOf(true, null)));
    }

    @Test
    void shouldAcceptWeightedFusionWhenTheGraphRouteIsOff() {
        assertDoesNotThrow(() -> policy.requireCompatible(configOf(false, FusionMode.WEIGHTED.code())));
        assertDoesNotThrow(() -> policy.requireCompatible(configOf(null, FusionMode.WEIGHTED.code())));
    }

    @Test
    void shouldAcceptAnAbsentConfiguration() {
        assertDoesNotThrow(() -> policy.requireCompatible(null));
    }

    private KbRetrievalConfig configOf(Boolean graphEnabled, String fusionMode) {
        KbRetrievalConfig config = new KbRetrievalConfig();
        config.setGraphEnabled(graphEnabled);
        config.setFusionMode(fusionMode);
        return config;
    }
}
