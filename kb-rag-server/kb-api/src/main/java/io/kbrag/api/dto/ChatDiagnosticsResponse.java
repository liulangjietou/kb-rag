package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.openapi.ChatDiagnostics;
import io.kbrag.app.retrieval.RerankTiming;
import io.kbrag.common.context.RequestIdHolder;

/** 只包含时间、阶段和关联标识，不附带提示词、模型密钥或租户用量账本。 */
public record ChatDiagnosticsResponse(
        @JsonProperty("request_id") String requestId,
        ChatDiagnostics.Outcome outcome,
        @JsonProperty("failed_stage") ChatDiagnostics.Stage failedStage,
        @JsonProperty("configuration_ms") Long configurationMs,
        @JsonProperty("retrieval_ms") Long retrievalMs,
        @JsonProperty("generation_ms") Long generationMs,
        @JsonProperty("first_delta_ms") Long firstDeltaMs,
        @JsonProperty("total_ms") long totalMs,
        @JsonProperty("rerank_status") RerankTiming.Status rerankStatus,
        @JsonProperty("rerank_ms") Long rerankMs) {

    /** 从同一次请求的测量快照生成 JSON 和 SSE 共用的诊断载荷。 */
    public static ChatDiagnosticsResponse from(ChatDiagnostics value) {
        return new ChatDiagnosticsResponse(RequestIdHolder.get(), value.outcome(), value.failedStage(),
                value.configurationMs(), value.retrievalMs(), value.generationMs(), value.firstDeltaMs(),
                value.totalMs(), value.rerank() == null ? null : value.rerank().status(),
                value.rerank() == null ? null : value.rerank().elapsedMs());
    }
}
