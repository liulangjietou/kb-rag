package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;

/**
 * Final-answer gate verdict and the tolerance/deltas behind it.
 *
 * @param verdict gate outcome
 * @param reason classified reason
 * @param scoreEpsilon tolerance on one-to-five scores
 * @param accuracyEpsilon tolerance on refusal accuracy
 * @param deltas candidate-minus-baseline metrics, {@code null} on a first release
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerGateDecision(
        GateVerdict verdict,
        GateReason reason,
        @JsonProperty("score_epsilon") double scoreEpsilon,
        @JsonProperty("accuracy_epsilon") double accuracyEpsilon,
        FinalAnswerMetricDeltas deltas) {

    /** Builds a decision without a baseline comparison. */
    public static FinalAnswerGateDecision of(GateVerdict verdict, GateReason reason) {
        return new FinalAnswerGateDecision(verdict, reason, 0.0d, 0.0d, null);
    }
}
