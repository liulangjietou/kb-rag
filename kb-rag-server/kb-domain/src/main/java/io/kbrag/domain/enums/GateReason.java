package io.kbrag.domain.enums;

/**
 * Classified reason behind a {@link GateVerdict}, stored next to it so the console can explain the
 * decision without re-deriving it, requirement section 4.7.
 *
 * <p>The four reasons that map onto {@link GateVerdict#LOG_ONLY} are exactly the four situations the
 * requirement groups under "record, do not block": no data set bound, too few effective cases, cases
 * still degraded after the automatic retry, and a stale evidence ratio above the threshold. A fifth
 * one is added for an evaluation run that failed outright, since a run that produced no metrics is by
 * definition not decidable either and the requirement asks for a retry rather than a block.
 *
 * @author owlzhangfq@gmail.com
 */
public enum GateReason {

    /** No baseline data set bound to the version, requirement section 4.7 "skip the gate, record it". */
    NO_DATASET("未绑定门禁评测集，本次发布未经门禁校验"),

    /** Effective case count below the configured minimum, the comparison would be noise. */
    INSUFFICIENT_CASES("有效 case 数量不足，门禁降级为仅记录，请扩充评测集"),

    /** Cases still carried degradation markers after the automatic retries. */
    DEGRADED_CASES("重试后仍存在降级 case，指标不可信，门禁降级为仅记录"),

    /** Stale evidence ratio above the configured threshold, the data set needs a review pass. */
    STALE_RATIO_EXCEEDED("待复核 case 占比超过阈值，评测集需复核后重跑"),

    /** One of the two runs did not finish successfully, so there is nothing to compare. */
    RUN_FAILED("门禁双跑未成功完成，可修复后重试"),

    /** The two runs did not measure the same data set revision or the same corpus state. */
    NOT_COMPARABLE("双跑期间评测集或语料发生变化，两次运行不可比"),

    /** Candidate metric fell below the baseline minus the tolerance. */
    METRICS_REGRESSED("候选配置核心指标低于对照减容差，门禁拦截"),

    /** Candidate stayed within the tolerance of the baseline. */
    WITHIN_TOLERANCE("候选配置核心指标未低于对照减容差，门禁通过"),

    /** First release with no baseline: the configured absolute threshold was met. */
    ABSOLUTE_THRESHOLD_MET("首次发布无对照，已达到配置的绝对阈值，门禁通过"),

    /** First release with no baseline: the configured absolute threshold was not met. */
    ABSOLUTE_THRESHOLD_NOT_MET("首次发布无对照，未达到配置的绝对阈值，门禁拦截"),

    /** First release with no baseline and no absolute threshold: metrics recorded as the baseline. */
    BASELINE_RECORDED("首次发布无对照且未配置绝对阈值，已记录本次结果作为后续基线并放行");

    private final String message;

    GateReason(String message) {
        this.message = message;
    }

    /**
     * Operator facing explanation, rendered by the console next to the verdict.
     *
     * @return explanation text
     */
    public String message() {
        return message;
    }
}
