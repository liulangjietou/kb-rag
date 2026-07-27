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

    private ChatAggregationParams aggregation(int windowMinutes, int maxMessages, int windowOverlap) {
        ChatAggregationParams params = new ChatAggregationParams();
        params.setWindowMinutes(windowMinutes);
        params.setMaxMessages(maxMessages);
        params.setWindowOverlap(windowOverlap);
        return params;
    }

    private UpdateIndexConfigRequest request(ChatAggregationParams aggregation) {
        return new UpdateIndexConfigRequest("fixed_length", 600, 100, null, null, null,
                aggregation, null, null, null);
    }
}
