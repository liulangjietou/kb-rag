package io.kbrag.app.openapi;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 用可控单调时钟验证边界，不依赖休眠或机器运行速度。 */
class PreviewTimingTest {

    @Test
    void shouldMeasureFirstNonEmptyDeltaFromRequestStartWithoutCountingItTwice() {
        AtomicLong clock = new AtomicLong();
        PreviewTiming timing = new PreviewTiming(clock::get);
        clock.set(nanos(12)); timing.start(ChatDiagnostics.Stage.RETRIEVAL);
        clock.set(nanos(252)); timing.start(ChatDiagnostics.Stage.GENERATION);
        clock.set(nanos(302)); timing.onDelta("");
        clock.set(nanos(602)); timing.onDelta("首段");
        clock.set(nanos(802)); timing.onDelta("后续");
        clock.set(nanos(1552));
        ChatDiagnostics result = timing.finish(ChatDiagnostics.Outcome.SUCCEEDED);

        assertEquals(new ChatDiagnostics(ChatDiagnostics.Outcome.SUCCEEDED, null, 12L, 240L,
                1300L, 602L, 1552L), result);
    }

    @Test
    void shouldKeepUnexecutedGenerationUnknownWhenRetrievalFails() {
        AtomicLong clock = new AtomicLong();
        PreviewTiming timing = new PreviewTiming(clock::get);
        clock.set(nanos(10)); timing.start(ChatDiagnostics.Stage.RETRIEVAL);
        clock.set(nanos(210));
        ChatDiagnostics result = timing.finish(ChatDiagnostics.Outcome.FAILED);

        assertEquals(ChatDiagnostics.Stage.RETRIEVAL, result.failedStage());
        assertEquals(200L, result.retrievalMs());
        assertNull(result.generationMs());
        assertNull(result.firstDeltaMs());
        assertEquals(210L, result.totalMs());
    }

    @Test
    void shouldReportCancelledAttemptAndPreserveSubMillisecondZeroAsMeasured() {
        AtomicLong clock = new AtomicLong();
        PreviewTiming timing = new PreviewTiming(clock::get);
        clock.set(900_000L);
        ChatDiagnostics result = timing.finish(ChatDiagnostics.Outcome.CANCELLED);

        assertEquals(ChatDiagnostics.Outcome.CANCELLED, result.outcome());
        assertEquals(ChatDiagnostics.Stage.CONFIGURATION, result.failedStage());
        assertEquals(0L, result.configurationMs());
        assertNull(result.retrievalMs());
        assertNull(result.generationMs());
    }

    private long nanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
