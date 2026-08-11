package io.kbrag.api.dto;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.ChatAggregationParams;
import io.kbrag.domain.model.KbIndexConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the single validation gate of the chat aggregation window: the overlap bound is checked here and
 * nowhere else, so an operator learns that a value is impossible instead of silently getting a different
 * window shape than the one they configured.
 *
 * @author owlzhangfq@gmail.com
 */
class UpdateIndexConfigRequestTest {

    @Test
    void shouldAcceptTheSequentialCutAsTheDefault() {
        KbIndexConfig config = request(aggregation(60, 50, 0)).toIndexConfig(new KbIndexConfig());

        assertEquals(0, config.chatAggregationOrDefaults().getWindowOverlap());
        assertEquals(0, config.chatAggregationOrDefaults().effectiveWindowOverlap());
    }

    @Test
    void shouldAcceptAnOverlapJustBelowHalfTheMessageCeiling() {
        KbIndexConfig config = request(aggregation(60, 50, 24)).toIndexConfig(new KbIndexConfig());

        assertEquals(24, config.chatAggregationOrDefaults().effectiveWindowOverlap());
    }

    @Test
    void shouldRejectAnOverlapOfExactlyHalfTheMessageCeiling() {
        BizException thrown = assertThrows(BizException.class,
                () -> request(aggregation(60, 50, 25)).toIndexConfig(new KbIndexConfig()));

        // Half or more would put a message in three windows or make the walk stop advancing.
        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
    }

    @Test
    void shouldRejectAnOverlapAboveHalfTheMessageCeiling() {
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> request(aggregation(60, 10, 7)).toIndexConfig(new KbIndexConfig())).getErrorCode());
    }

    @Test
    void shouldRejectANegativeOverlap() {
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> request(aggregation(60, 50, -1)).toIndexConfig(new KbIndexConfig())).getErrorCode());
    }

    @Test
    void shouldStillRejectANonPositiveMessageCeiling() {
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> request(aggregation(60, 0, 0)).toIndexConfig(new KbIndexConfig())).getErrorCode());
    }

    @Test
    void shouldKeepTheStoredAggregationWhenThePayloadCarriesNone() {
        KbIndexConfig current = new KbIndexConfig();
        current.setChatAggregation(aggregation(30, 20, 5));

        KbIndexConfig config = request(null).toIndexConfig(current);

        assertEquals(5, config.chatAggregationOrDefaults().getWindowOverlap());
    }

    @Test
    void shouldSetTheMultimodalSwitchFromThePayload() {
        KbIndexConfig config = multimodalRequest(true).toIndexConfig(new KbIndexConfig());

        // The switch is stored whatever the provider state is: the build stage, not this gate, decides
        // whether the vectors can be produced.
        assertEquals(true, config.isMultimodalEnabled());
    }

    @Test
    void shouldKeepTheStoredMultimodalSwitchWhenThePayloadCarriesNone() {
        KbIndexConfig current = new KbIndexConfig();
        current.setMultimodalEnabled(true);

        // A save that never mentions the switch must not silently turn the capability off.
        KbIndexConfig config = multimodalRequest(null).toIndexConfig(current);

        assertEquals(true, config.isMultimodalEnabled());
    }

    @Test
    void shouldAcceptTheStrategiesThatShipWithAnImplementation() {
        // The M14 strategies were rejected on this path while their splitters sat registered in the
        // container, which made the console forms and the beans dead weight.
        assertEquals("page", strategyRequest("page", null).toIndexConfig(new KbIndexConfig())
                .getSplitStrategy());
        assertEquals("heading", strategyRequest("heading", null).toIndexConfig(new KbIndexConfig())
                .getSplitStrategy());
        assertEquals("separator", strategyRequest("separator", null).toIndexConfig(new KbIndexConfig())
                .getSplitStrategy());
    }

    @Test
    void shouldRejectLlmSemanticOnATwoLevelBase() {
        // The two level splitter composes fixed length with itself and is not parameterised by the
        // configured strategy, so this combination recorded llm_semantic in the split fingerprint while
        // fixed length actually ran - a configuration that reads as one thing and indexes as another.
        BizException thrown = assertThrows(BizException.class,
                () -> strategyRequest("llm_semantic", parentChild()).toIndexConfig(new KbIndexConfig()));

        assertEquals(ErrorCode.INVALID_PARAM, thrown.getErrorCode());
    }

    @Test
    void shouldRejectThePageStrategyOnATwoLevelBase() {
        // The page strategy cuts on a boundary the parent pass cannot honour without losing it.
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> strategyRequest("page", parentChild()).toIndexConfig(new KbIndexConfig()))
                .getErrorCode());
    }

    @Test
    void shouldStillAcceptFixedLengthOnATwoLevelBase() {
        KbIndexConfig config = strategyRequest("fixed_length", parentChild())
                .toIndexConfig(new KbIndexConfig());

        assertEquals(true, config.parentChildOrDisabled().isEnabled());
        assertEquals("fixed_length", config.getSplitStrategy());
    }

    private UpdateIndexConfigRequest.ParentChildRequest parentChild() {
        return new UpdateIndexConfigRequest.ParentChildRequest(true, 1200, 300, 50);
    }

    private UpdateIndexConfigRequest strategyRequest(String strategy,
                                                     UpdateIndexConfigRequest.ParentChildRequest parentChild) {
        return new UpdateIndexConfigRequest(strategy, 600, 100, parentChild, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private ChatAggregationParams aggregation(int windowMinutes, int maxMessages, int windowOverlap) {
        ChatAggregationParams params = new ChatAggregationParams();
        params.setWindowMinutes(windowMinutes);
        params.setMaxMessages(maxMessages);
        params.setWindowOverlap(windowOverlap);
        return params;
    }

    private UpdateIndexConfigRequest request(ChatAggregationParams aggregation) {
        return new UpdateIndexConfigRequest("fixed_length", 600, 100, null, null, null,
                aggregation, null, null, null, null, null, null, null, null);
    }

    private UpdateIndexConfigRequest multimodalRequest(Boolean multimodalEnabled) {
        return new UpdateIndexConfigRequest("fixed_length", 600, 100, null, null, null,
                null, null, null, null, null, null, null, multimodalEnabled, null);
    }
}
