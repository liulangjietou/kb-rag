package io.kbrag.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the release state machine of requirement section 4.7, including the moves that must stay illegal:
 * a draft cannot be released, a retired version cannot be re-gated, and no status may transition to itself.
 *
 * @author owlzhangfq@gmail.com
 */
class AppVersionStatusTest {

    @Test
    void shouldAllowTheHappyPathOfTheStateMachine() {
        assertTrue(AppVersionStatus.DRAFT.canTransitionTo(AppVersionStatus.TESTING));
        assertTrue(AppVersionStatus.TESTING.canTransitionTo(AppVersionStatus.GATING));
        assertTrue(AppVersionStatus.GATING.canTransitionTo(AppVersionStatus.GATE_PASSED));
        assertTrue(AppVersionStatus.GATE_PASSED.canTransitionTo(AppVersionStatus.RELEASED));
        assertTrue(AppVersionStatus.RELEASED.canTransitionTo(AppVersionStatus.SUPERSEDED));
    }

    @Test
    void shouldAllowARollbackFromRetiredBackToReleased() {
        assertTrue(AppVersionStatus.SUPERSEDED.canTransitionTo(AppVersionStatus.RELEASED));
    }

    @Test
    void shouldAllowAForcedReleaseFromEitherNonPassingGateOutcome() {
        assertTrue(AppVersionStatus.GATE_LOG_ONLY.canTransitionTo(AppVersionStatus.RELEASED));
        assertTrue(AppVersionStatus.GATE_BLOCKED.canTransitionTo(AppVersionStatus.RELEASED));
    }

    @Test
    void shouldAllowReGatingAfterANonPassingOutcome() {
        assertTrue(AppVersionStatus.GATE_LOG_ONLY.canTransitionTo(AppVersionStatus.GATING));
        assertTrue(AppVersionStatus.GATE_BLOCKED.canTransitionTo(AppVersionStatus.GATING));
    }

    @Test
    void shouldAllowRecordingAGateOutcomeDirectlyFromTheTestVersion() {
        // The no data set path decides synchronously and never passes through GATING.
        assertTrue(AppVersionStatus.TESTING.canTransitionTo(AppVersionStatus.GATE_LOG_ONLY));
    }

    @Test
    void shouldRefuseToReleaseADraft() {
        assertFalse(AppVersionStatus.DRAFT.canTransitionTo(AppVersionStatus.RELEASED));
        assertFalse(AppVersionStatus.DRAFT.canTransitionTo(AppVersionStatus.GATING));
    }

    @Test
    void shouldRefuseToReleaseWhileTheGateIsStillRunning() {
        assertFalse(AppVersionStatus.GATING.canTransitionTo(AppVersionStatus.RELEASED));
    }

    @Test
    void shouldRefuseToReviveARetiredVersionThroughAnyOtherPath() {
        assertFalse(AppVersionStatus.SUPERSEDED.canTransitionTo(AppVersionStatus.TESTING));
        assertFalse(AppVersionStatus.SUPERSEDED.canTransitionTo(AppVersionStatus.GATING));
        assertFalse(AppVersionStatus.SUPERSEDED.canTransitionTo(AppVersionStatus.DRAFT));
    }

    @Test
    void shouldRefuseATransitionToItselfAndToNull() {
        for (AppVersionStatus status : AppVersionStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " must not transition to itself");
            assertFalse(status.canTransitionTo(null), status + " must not transition to null");
        }
    }

    @Test
    void shouldExposeOnlyTheReleasedAndTestVersionAsCallable() {
        assertTrue(AppVersionStatus.RELEASED.callable());
        assertTrue(AppVersionStatus.TESTING.callable());
        assertFalse(AppVersionStatus.SUPERSEDED.callable());
        assertFalse(AppVersionStatus.DRAFT.callable());
        assertFalse(AppVersionStatus.GATING.callable());
        assertFalse(AppVersionStatus.GATE_PASSED.callable());
        assertFalse(AppVersionStatus.GATE_LOG_ONLY.callable());
        assertFalse(AppVersionStatus.GATE_BLOCKED.callable());
    }

    @Test
    void shouldAllowConfigurationEditingOnlyOnADraft() {
        assertTrue(AppVersionStatus.DRAFT.configEditable());
        assertFalse(AppVersionStatus.TESTING.configEditable());
        assertFalse(AppVersionStatus.RELEASED.configEditable());
    }

    @Test
    void shouldRecogniseTheThreeGateOutcomes() {
        assertTrue(AppVersionStatus.GATE_PASSED.gateOutcome());
        assertTrue(AppVersionStatus.GATE_LOG_ONLY.gateOutcome());
        assertTrue(AppVersionStatus.GATE_BLOCKED.gateOutcome());
        assertFalse(AppVersionStatus.GATING.gateOutcome());
        assertFalse(AppVersionStatus.TESTING.gateOutcome());
    }

    @Test
    void shouldMapEachVerdictOntoItsStatus() {
        assertTrue(GateVerdict.PASSED.autoReleasable());
        assertFalse(GateVerdict.LOG_ONLY.autoReleasable());
        assertFalse(GateVerdict.BLOCKED.autoReleasable());
        assertTrue(GateVerdict.PASSED.status() == AppVersionStatus.GATE_PASSED);
        assertTrue(GateVerdict.BLOCKED.status() == AppVersionStatus.GATE_BLOCKED);
        assertTrue(GateVerdict.LOG_ONLY.status() == AppVersionStatus.GATE_LOG_ONLY);
    }

    @Test
    void shouldMapAVersionStatusOntoItsAuditStage() {
        assertTrue(TargetStage.of(AppVersionStatus.RELEASED) == TargetStage.RELEASE);
        assertTrue(TargetStage.of(AppVersionStatus.TESTING) == TargetStage.BETA);
        assertTrue(TargetStage.from("release") == TargetStage.RELEASE);
        assertTrue(TargetStage.from("BETA") == TargetStage.BETA);
    }
}
