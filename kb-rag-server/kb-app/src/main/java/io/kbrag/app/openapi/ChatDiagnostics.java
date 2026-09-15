package io.kbrag.app.openapi;

import io.kbrag.app.retrieval.RerankTiming;

/** 预览请求的单调时钟测量；未执行的阶段和未收到的首段保持为空。 */
public record ChatDiagnostics(Outcome outcome, Stage failedStage, Long configurationMs,
                              Long retrievalMs, Long generationMs, Long firstDeltaMs, long totalMs,
                              RerankTiming rerank) {

    /** 兼容未单独采集重排的调用，缺失的子阶段保持为空。 */
    public ChatDiagnostics(Outcome outcome, Stage failedStage, Long configurationMs,
                           Long retrievalMs, Long generationMs, Long firstDeltaMs, long totalMs) {
        this(outcome, failedStage, configurationMs, retrievalMs, generationMs, firstDeltaMs, totalMs, null);
    }

    /** 编排阶段；重排子段包含在 RETRIEVAL 总段内，不能再次相加。 */
    public enum Stage { CONFIGURATION, RETRIEVAL, GENERATION }

    /** 请求最终结果，不以收到诊断事件代表回答成功。 */
    public enum Outcome { SUCCEEDED, FAILED, CANCELLED }
}
