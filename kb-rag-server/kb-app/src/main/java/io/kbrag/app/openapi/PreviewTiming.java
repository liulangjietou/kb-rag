package io.kbrag.app.openapi;

import io.kbrag.app.retrieval.RerankTiming;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 每次预览独占的阶段计时器，不使用线程上下文或持久化状态。 */
final class PreviewTiming {

    private final LongSupplier clock;
    private final long startedAt;
    private final EnumMap<ChatDiagnostics.Stage, Long> elapsed = new EnumMap<>(ChatDiagnostics.Stage.class);
    private ChatDiagnostics.Stage stage = ChatDiagnostics.Stage.CONFIGURATION;
    private long stageStartedAt;
    private Long firstDeltaMs;
    private RerankTiming rerank;

    PreviewTiming() {
        this(System::nanoTime);
    }

    PreviewTiming(LongSupplier clock) {
        this.clock = clock;
        startedAt = clock.getAsLong();
        stageStartedAt = startedAt;
    }

    void start(ChatDiagnostics.Stage next) {
        long now = clock.getAsLong();
        elapsed.put(stage, milliseconds(now - stageStartedAt));
        stage = next;
        stageStartedAt = now;
    }

    void onDelta(String delta) {
        if (firstDeltaMs == null && delta != null && !delta.isEmpty()) {
            firstDeltaMs = milliseconds(clock.getAsLong() - startedAt);
        }
    }

    void recordRerank(RerankTiming measurement) {
        rerank = measurement;
    }

    ChatDiagnostics finish(ChatDiagnostics.Outcome outcome) {
        long now = clock.getAsLong();
        elapsed.put(stage, milliseconds(now - stageStartedAt));
        return new ChatDiagnostics(outcome, outcome == ChatDiagnostics.Outcome.SUCCEEDED ? null : stage,
                elapsed.get(ChatDiagnostics.Stage.CONFIGURATION), elapsed.get(ChatDiagnostics.Stage.RETRIEVAL),
                elapsed.get(ChatDiagnostics.Stage.GENERATION), firstDeltaMs, milliseconds(now - startedAt), rerank);
    }

    private long milliseconds(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
