package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.DegradedReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 计时取整与跳过语义分开，模型超时或失败仍保留已等待的时间。 */
class RerankTimingTest {

    @Test
    void shouldKeepMeasuredSubMillisecondZero() {
        assertEquals(new RerankTiming(RerankTiming.Status.APPLIED, 0L), RerankTiming.fromOutcome(
                RerankOutcome.applied(List.of(0.8d)), true, true, false, 900_000));
    }

    @Test
    void shouldKeepElapsedTimeWhenFallingBackAfterTimeoutOrFailure() {
        assertEquals(new RerankTiming(RerankTiming.Status.TIMEOUT, 25L), RerankTiming.fromOutcome(
                RerankOutcome.degraded(DegradedReason.RERANK_TIMEOUT.code()), true, true, false, 25_999_999));
        assertEquals(new RerankTiming(RerankTiming.Status.FAILED, 0L), RerankTiming.fromOutcome(
                RerankOutcome.degraded(DegradedReason.RERANK_ERROR.code()), true, true, false, 900_000));
    }

    @Test
    void shouldNotTurnSkippedBranchesIntoZeroDuration() {
        var skipped = RerankOutcome.skipped();
        assertEquals(new RerankTiming(RerankTiming.Status.DISABLED, null),
                RerankTiming.fromOutcome(skipped, false, true, false, 900_000));
        assertEquals(new RerankTiming(RerankTiming.Status.EMPTY_CANDIDATES, null),
                RerankTiming.fromOutcome(skipped, true, false, true, 900_000));
        assertEquals(new RerankTiming(RerankTiming.Status.UNAVAILABLE, null),
                RerankTiming.fromOutcome(skipped, true, false, false, 900_000));
        assertEquals(new RerankTiming(RerankTiming.Status.SKIPPED, null),
                RerankTiming.fromOutcome(skipped, true, true, false, 900_000));
    }

    @Test
    void shouldPreferActualUnavailableResultOverEarlierAvailabilityProbe() {
        var timing = RerankTiming.fromOutcome(RerankOutcome.degraded(DegradedReason.RERANK_UNAVAILABLE.code()),
                true, true, false, 5_000_000);
        assertEquals(RerankTiming.Status.UNAVAILABLE, timing.status());
        assertNull(timing.elapsedMs());
    }
}
