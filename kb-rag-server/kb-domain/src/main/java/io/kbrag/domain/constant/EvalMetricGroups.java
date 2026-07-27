package io.kbrag.domain.constant;

/**
 * Group key literals of the {@code t_kb_eval_run.metrics} JSON document.
 *
 * <p>Fixed by the web console contract: the outer map of the metrics document uses exactly these five
 * literals as keys, and a group with no case in it is omitted rather than emitted with zero values,
 * so the report page can tell "no multi turn case exists" from "the multi turn group scored zero".
 *
 * @author owlzhangfq@gmail.com
 */
public final class EvalMetricGroups {

    /** Every effective case of the run. */
    public static final String OVERALL = "overall";

    /** Span anchored cases only. */
    public static final String SPAN = "span";

    /** Document anchored cases only. */
    public static final String DOCUMENT = "document";

    /** Cases with no conversation history. */
    public static final String SINGLE_TURN = "single_turn";

    /** Cases carrying a multi turn conversation. */
    public static final String MULTI_TURN = "multi_turn";

    private EvalMetricGroups() {
    }
}
