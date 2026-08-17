package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Opt-in final-answer release gate configuration frozen into an application version.
 *
 * <p>Disabled is the backward-compatible default: versions created before M21 were never evaluated on
 * generated answers, so an upgrade must not silently introduce a non-deterministic blocking criterion.
 * Once enabled, a first release uses the configured absolute thresholds and later releases compare the
 * candidate with the current release on the same cases.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnswerGateConfig {

    /** Explicit opt-in switch. */
    private boolean enabled;

    /** Minimum overall score of a first release, on the one-to-five scale. */
    @JsonProperty("min_score")
    private Double minScore;

    /** Minimum faithfulness score of a first release. */
    @JsonProperty("min_faithfulness")
    private Double minFaithfulness;

    /** Minimum citation correctness score of a first release. */
    @JsonProperty("min_citation_correctness")
    private Double minCitationCorrectness;

    /** Minimum correct answer/refuse decision ratio of a first release. */
    @JsonProperty("min_refusal_accuracy")
    private Double minRefusalAccuracy;

    /**
     * Tells whether a first release has an absolute answer threshold to meet.
     *
     * @return {@code true} when at least one threshold is configured
     */
    public boolean thresholdsConfigured() {
        return minScore != null || minFaithfulness != null || minCitationCorrectness != null
                || minRefusalAccuracy != null;
    }
}
