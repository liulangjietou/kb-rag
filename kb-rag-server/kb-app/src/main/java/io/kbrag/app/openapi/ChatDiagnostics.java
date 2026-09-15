package io.kbrag.app.openapi;

/** 预览请求的单调时钟测量；未执行的阶段和未收到的首段保持为空。 */
public record ChatDiagnostics(Outcome outcome, Stage failedStage, Long configurationMs,
                              Long retrievalMs, Long generationMs, Long firstDeltaMs, long totalMs) {

    /** 编排阶段；检索内部的改写、召回和重排合并测量。 */
    public enum Stage { CONFIGURATION, RETRIEVAL, GENERATION }

    /** 请求最终结果，不以收到诊断事件代表回答成功。 */
    public enum Outcome { SUCCEEDED, FAILED, CANCELLED }
}
