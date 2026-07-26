package io.kbrag.domain.service;

import io.kbrag.domain.model.GateCaseOutcome;
import io.kbrag.domain.model.GateComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the intersection recomputation of requirement section 4.7 "effective case definition": both sides
 * are scored on the cases both of them judged, so a case one side dropped cannot move the difference.
 *
 * @author owlzhangfq@gmail.com
 */
class GateMetricsRecomputerTest {

    private final GateMetricsRecomputer recomputer = new GateMetricsRecomputer();

    @Test
    void shouldScoreBothSidesOnTheirCommonCasesOnly() {
        // c1 and c2 are common; c3 exists only for the candidate and c4 only for the baseline. Scoring each
        // side on its own set would give the candidate 2/3 and the baseline 1/3; on the intersection both
        // sides are judged on c1 and c2 alone.
        List<GateCaseOutcome> candidate = List.of(
                hit("c1"), miss("c2"), hit("c3"));
        List<GateCaseOutcome> baseline = List.of(
                hit("c1"), miss("c2"), miss("c4"));

        GateComparison comparison = recomputer.recompute(candidate, baseline);

        assertEquals(2, comparison.effectiveCases());
        assertEquals(List.of("c1", "c2"), comparison.caseIds());
        assertEquals(0.5d, comparison.candidate().hitRate(), 1e-9d);
        assertEquals(0.5d, comparison.baseline().hitRate(), 1e-9d);
    }

    @Test
    void shouldAverageThePerCaseRecallFractionOnTheIntersection() {
        // Candidate covers 1 of 2 evidences on c1 and 2 of 2 on c2: mean recall 0.75. The baseline covers
        // nothing on c1 and 1 of 2 on c2: mean recall 0.25.
        List<GateCaseOutcome> candidate = List.of(
                new GateCaseOutcome("c1", true, 1, 2, false),
                new GateCaseOutcome("c2", true, 2, 2, false));
        List<GateCaseOutcome> baseline = List.of(
                new GateCaseOutcome("c1", false, 0, 2, false),
                new GateCaseOutcome("c2", true, 1, 2, false));

        GateComparison comparison = recomputer.recompute(candidate, baseline);

        assertEquals(0.75d, comparison.candidate().recall(), 1e-9d);
        assertEquals(0.25d, comparison.baseline().recall(), 1e-9d);
        assertEquals(1.0d, comparison.candidate().hitRate(), 1e-9d);
        assertEquals(0.5d, comparison.baseline().hitRate(), 1e-9d);
    }

    @Test
    void shouldCountACaseDegradedOnEitherSideExactlyOnce() {
        List<GateCaseOutcome> candidate = List.of(
                new GateCaseOutcome("c1", true, 1, 1, true),
                new GateCaseOutcome("c2", true, 1, 1, true),
                new GateCaseOutcome("c3", true, 1, 1, false));
        List<GateCaseOutcome> baseline = List.of(
                new GateCaseOutcome("c1", true, 1, 1, true),
                new GateCaseOutcome("c2", true, 1, 1, false),
                new GateCaseOutcome("c3", true, 1, 1, false));

        GateComparison comparison = recomputer.recompute(candidate, baseline);

        assertEquals(2, comparison.degradedCases());
        assertEquals(3, comparison.effectiveCases());
    }

    @Test
    void shouldReportNoBaselineWhenTheBaselineSideIsAbsent() {
        GateComparison comparison = recomputer.recompute(List.of(hit("c1"), miss("c2")), List.of());

        assertFalse(comparison.hasBaseline());
        assertNull(comparison.baseline());
        assertEquals(2, comparison.effectiveCases());
        assertEquals(0.5d, comparison.candidate().hitRate(), 1e-9d);
    }

    @Test
    void shouldYieldAnEmptyIntersectionWhenNothingOverlaps() {
        GateComparison comparison = recomputer.recompute(List.of(hit("c1")), List.of(hit("c2")));

        assertEquals(0, comparison.effectiveCases());
        assertTrue(comparison.caseIds().isEmpty());
        assertEquals(0.0d, comparison.candidate().hitRate(), 1e-9d);
    }

    @Test
    void shouldTreatACaseWithNoDeclaredEvidenceAsZeroRecall() {
        GateComparison comparison = recomputer.recompute(
                List.of(new GateCaseOutcome("c1", true, 0, 0, false)),
                List.of(new GateCaseOutcome("c1", true, 0, 0, false)));

        assertEquals(0.0d, comparison.candidate().recall(), 1e-9d);
        assertEquals(1.0d, comparison.candidate().hitRate(), 1e-9d);
    }

    private GateCaseOutcome hit(String caseId) {
        return new GateCaseOutcome(caseId, true, 1, 1, false);
    }

    private GateCaseOutcome miss(String caseId) {
        return new GateCaseOutcome(caseId, false, 0, 1, false);
    }
}
