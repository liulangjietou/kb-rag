package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result of recomputing both sides of a gate dual run on the intersection of their effective cases,
 * requirement section 4.7 "the comparison is recomputed on the intersection of the effective cases of
 * both sides".
 *
 * <p>Recomputing rather than reading the two runs' stored metrics closes a real hole: each run reports
 * metrics over its own effective set, and if one side dropped a case as stale the two denominators
 * differ, so the difference between the published numbers is partly a denominator artefact rather than a
 * quality change.
 *
 * @param candidate      metrics of the candidate configuration on the intersection
 * @param baseline       metrics of the released configuration on the intersection, {@code null} on a
 *                       first release that has no baseline
 * @param effectiveCases size of the intersection, the denominator of both sides
 * @param degradedCases  cases either side reported as still degraded after its retries
 * @param caseIds        the intersection itself, kept for the drill down of the report page
 *
 * @author owlzhangfq@gmail.com
 */
public record GateComparison(
        GateCoreMetrics candidate,
        GateCoreMetrics baseline,
        @JsonProperty("effective_cases") int effectiveCases,
        @JsonProperty("degraded_cases") int degradedCases,
        @JsonProperty("case_ids") List<String> caseIds) {

    /**
     * Tells whether a baseline side exists at all.
     *
     * @return {@code true} when this is a comparison and not a first release measurement
     */
    public boolean hasBaseline() {
        return baseline != null;
    }
}
