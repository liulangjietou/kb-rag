package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.DegradedReason;

import java.util.concurrent.TimeUnit;

/** 重排同步调用的实测结果；没有调度模型的分支不填零耗时。 */
public record RerankTiming(Status status, Long elapsedMs) {

    /** 应用分数、跳过原因与降级原因分开，重排降级不等于整轮检索失败。 */
    public enum Status { APPLIED, DISABLED, EMPTY_CANDIDATES, UNAVAILABLE, TIMEOUT, FAILED, SKIPPED }

    /** 按实际结果优先判断，配置仅用于解释没有返回分数或降级标志的跳过分支。 */
    static RerankTiming fromOutcome(RerankOutcome outcome, boolean enabled, boolean available,
                                    boolean emptyCandidates, long elapsedNanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (outcome.isApplied()) {
            return new RerankTiming(Status.APPLIED, millis);
        }
        if (DegradedReason.RERANK_TIMEOUT.code().equals(outcome.getDegradedReason())) {
            return new RerankTiming(Status.TIMEOUT, millis);
        }
        if (DegradedReason.RERANK_ERROR.code().equals(outcome.getDegradedReason())) {
            return new RerankTiming(Status.FAILED, millis);
        }
        if (DegradedReason.RERANK_UNAVAILABLE.code().equals(outcome.getDegradedReason())) {
            return new RerankTiming(Status.UNAVAILABLE, null);
        }
        if (!enabled) {
            return new RerankTiming(Status.DISABLED, null);
        }
        if (emptyCandidates) {
            return new RerankTiming(Status.EMPTY_CANDIDATES, null);
        }
        return new RerankTiming(available ? Status.SKIPPED : Status.UNAVAILABLE, null);
    }
}
