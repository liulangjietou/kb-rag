package io.kbrag.app.appcenter;

import io.kbrag.app.eval.EvalRunService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.EvalMode;
import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.model.GateDecision;
import io.kbrag.domain.model.GateReport;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.service.GateMetricsRecomputer;
import io.kbrag.domain.service.ReleaseGateJudge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the release gate orchestration of requirement section 4.7: the dual run reuses the evaluation runner,
 * the comparison is recomputed on the intersection, a passing verdict releases automatically, and a non passing
 * one needs a recorded forced release.
 *
 * @author owlzhangfq@gmail.com
 */
class ReleaseGateServiceTest {

    private static final String APP_ID = "app_1";
    private static final String VERSION_ID = "av_2";
    private static final String BASELINE_VERSION_ID = "av_1";
    private static final String DATASET_ID = "evds_1";
    private static final String CANDIDATE_RUN = "evr_candidate";
    private static final String BASELINE_RUN = "evr_baseline";
    private static final String KB_ID = "kb_1";
    private static final String SNAPSHOT_INDEX = "kb_1_none_s1";
    private static final String GATE_PROBE_THREAD = "gate-probe";
    private static final int HAND_OFF_TIMEOUT_SECONDS = 5;

    /** A gate pool whose queue is full: {@code execute} aborts, it never runs the task. */
    private static final Executor SATURATED_EXECUTOR = task -> {
        throw new RejectedExecutionException("gate pool saturated");
    };

    /** Frozen snapshot every successful release in this class is expected to install. */
    private static final AppReleaseSnapshotService.ReleaseSnapshot RELEASE_SNAPSHOT =
            new AppReleaseSnapshotService.ReleaseSnapshot(
                    List.of(new AppIndexSnapshot(KB_ID, "es", SNAPSHOT_INDEX)),
                    Map.of(KB_ID, List.of("dv_1")));

    private AppVersionService appVersionService;
    private AppReleaseSnapshotService releaseSnapshotService;
    private EvalRunService evalRunService;
    private EvalResultMapper evalResultMapper;
    private EmbeddingProvider embeddingProvider;
    private RerankProvider rerankProvider;
    private KbProperties properties;
    private ReleaseGateService service;

    @BeforeEach
    void setUp() {
        appVersionService = mock(AppVersionService.class);
        releaseSnapshotService = mock(AppReleaseSnapshotService.class);
        evalRunService = mock(EvalRunService.class);
        evalResultMapper = mock(EvalResultMapper.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        rerankProvider = mock(RerankProvider.class);
        properties = new KbProperties();
        properties.getGate().setMinCases(2);
        when(releaseSnapshotService.freeze(any(), any())).thenReturn(RELEASE_SNAPSHOT);
        service = newService(Runnable::run);
    }

    /**
     * Builds the service over the executor the gate is submitted to.
     *
     * <p>Inline for the suite, so a {@code release} that starts a gate has finished it by the time the call
     * returns and the verdict can simply be asserted; the hand-off itself is measured separately.
     *
     * @param gateExecutor pool one gate's dual run supervision is submitted to
     * @return service under test
     */
    private ReleaseGateService newService(Executor gateExecutor) {
        return new ReleaseGateService(appVersionService, releaseSnapshotService, evalRunService,
                evalResultMapper, new GateMetricsRecomputer(), new ReleaseGateJudge(), embeddingProvider,
                rerankProvider, properties, gateExecutor);
    }

    @Test
    void shouldRecordAGateLogOnlyOutcomeWhenNoDataSetIsBoundAndNotRelease() {
        AppVersion version = versionOf(AppVersionStatus.TESTING, null);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());

        service.release(VERSION_ID, false, "admin");

        verify(appVersionService).recordGate(eq(version), eq(AppVersionStatus.GATE_LOG_ONLY),
                eq(GateVerdict.LOG_ONLY), eq(GateReason.NO_DATASET), anyString(), eq(null));
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void shouldReleaseAnUngatedVersionOnlyWhenTheCallerForcesIt() {
        AppVersion version = versionOf(AppVersionStatus.TESTING, null);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());

        service.release(VERSION_ID, true, "admin");

        verify(appVersionService).recordGate(eq(version), eq(AppVersionStatus.GATE_LOG_ONLY),
                eq(GateVerdict.LOG_ONLY), eq(GateReason.NO_DATASET), anyString(), eq(null));
        verify(appVersionService).promote(VERSION_ID, true, "admin", RELEASE_SNAPSHOT);
    }

    @Test
    void shouldPromoteAPassedVersionWithoutForcingAndWithoutReGating() {
        AppVersion version = versionOf(AppVersionStatus.GATE_PASSED, DATASET_ID);
        version.setGateVerdict(GateVerdict.PASSED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);

        service.release(VERSION_ID, false, "admin");

        verify(appVersionService).promote(VERSION_ID, false, "admin", RELEASE_SNAPSHOT);
        verify(evalRunService, never()).submit(anyString(), anyInt(), anyList(), anyBoolean());
    }

    @Test
    void shouldFreezeTheIndexSnapshotBeforeTheVersionBecomesReleased() {
        AppVersion version = versionOf(AppVersionStatus.GATE_PASSED, DATASET_ID);
        version.setGateVerdict(GateVerdict.PASSED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());

        service.release(VERSION_ID, false, "admin");

        // Requirement section 4.7 fixes the order: the gate first, the snapshot second, the state transition
        // third. Later would publish a version serving the live index for a moment.
        InOrder order = inOrder(releaseSnapshotService, appVersionService);
        order.verify(releaseSnapshotService).freeze(eq(version), any());
        order.verify(appVersionService).promote(VERSION_ID, false, "admin", RELEASE_SNAPSHOT);
    }

    @Test
    void shouldLeaveTheVersionInItsGateOutcomeStateWhenTheSnapshotFails() {
        AppVersion version = versionOf(AppVersionStatus.GATE_PASSED, DATASET_ID);
        version.setGateVerdict(GateVerdict.PASSED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(releaseSnapshotService.freeze(any(), any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "snapshot failed"));

        assertThrows(BizException.class, () -> service.release(VERSION_ID, false, "admin"));

        // Nothing was promoted and the status was never touched, so the version stays retryable exactly where
        // the gate left it. The freeze itself rolled back whatever indices it had created.
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
        assertEquals(AppVersionStatus.GATE_PASSED, version.getStatus());
    }

    @Test
    void shouldRollBackTheFrozenIndexesWhenTheStateTransitionFails() {
        AppVersion version = versionOf(AppVersionStatus.GATE_PASSED, DATASET_ID);
        version.setGateVerdict(GateVerdict.PASSED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.promote(anyString(), anyBoolean(), any(), any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "released slot taken"));

        assertThrows(BizException.class, () -> service.release(VERSION_ID, false, "admin"));

        // A snapshot no version references is dead storage nothing would ever clean up, so it goes away with
        // the failed transition.
        verify(releaseSnapshotService).discard(RELEASE_SNAPSHOT);
    }

    @Test
    void shouldRecordAForcedReleaseOverABlockingVerdictWithoutReRunningTheGate() {
        AppVersion version = versionOf(AppVersionStatus.GATE_BLOCKED, DATASET_ID);
        version.setGateVerdict(GateVerdict.BLOCKED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);

        service.release(VERSION_ID, true, "admin");

        // Forcing overrides a known verdict; re-running the comparison would replace the very evidence the
        // override is recorded against.
        verify(appVersionService).promote(VERSION_ID, true, "admin", RELEASE_SNAPSHOT);
        verify(evalRunService, never()).submit(anyString(), anyInt(), anyList(), anyBoolean());
    }

    @Test
    void shouldRefuseToReleaseADraftOrAVersionWhoseGateIsStillRunning() {
        when(appVersionService.require(VERSION_ID)).thenReturn(versionOf(AppVersionStatus.DRAFT, null));
        assertThrows(io.kbrag.common.exception.BizException.class,
                () -> service.release(VERSION_ID, false, "admin"));

        when(appVersionService.require(VERSION_ID)).thenReturn(versionOf(AppVersionStatus.GATING, DATASET_ID));
        assertThrows(io.kbrag.common.exception.BizException.class,
                () -> service.release(VERSION_ID, false, "admin"));

        when(appVersionService.require(VERSION_ID)).thenReturn(versionOf(AppVersionStatus.SUPERSEDED, null));
        assertThrows(io.kbrag.common.exception.BizException.class,
                () -> service.release(VERSION_ID, false, "admin"));
    }

    @Test
    void shouldStopACrossTenantReleaseAtTheVersionResolution() {
        // The tenant of an application version is resolved by AppVersionService#require through the fenced
        // root table, which is the whole of the isolation on this path: this service holds nothing but an
        // appVersionId. What matters here is where the rejection lands - before the gate. Releasing was the
        // single most expensive entry to leave unguarded: it does not only publish another tenant's version,
        // it starts a dual evaluation run on the gate executor, spending their retrieval and model calls.
        when(appVersionService.require(VERSION_ID))
                .thenThrow(new BizException(ErrorCode.VERSION_NOT_FOUND, "application version not found"));

        BizException e = assertThrows(BizException.class, () -> service.release(VERSION_ID, true, "admin"));

        assertEquals(ErrorCode.VERSION_NOT_FOUND, e.getErrorCode());
        verify(appVersionService, never()).markGating(any());
        verify(evalRunService, never()).submit(anyString(), anyInt(), anyList(), anyBoolean());
        verify(releaseSnapshotService, never()).freeze(any(), any());
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRunBothSidesOnTheSameDataSetAndReleaseWhenTheCandidateHoldsUp() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        AppVersion baseline = baselineVersion();
        stubDualRun(version, baseline);
        // Candidate and baseline agree on both cases, so nothing regressed.
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", false, 0, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", false, 0, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.WITHIN_TOLERANCE, decision.reason());
        verify(appVersionService).promote(VERSION_ID, false, null, RELEASE_SNAPSHOT);

        ArgumentCaptor<String> runIdsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reportCaptor = ArgumentCaptor.forClass(String.class);
        verify(appVersionService).recordGate(any(), eq(AppVersionStatus.GATE_PASSED), eq(GateVerdict.PASSED),
                eq(GateReason.WITHIN_TOLERANCE), reportCaptor.capture(), runIdsCaptor.capture());
        // Candidate first, baseline second: the console's comparison panel relies on that order.
        assertTrue(runIdsCaptor.getValue().indexOf(CANDIDATE_RUN) < runIdsCaptor.getValue().indexOf(BASELINE_RUN));
        GateReport report = JsonUtil.parse(reportCaptor.getValue(), GateReport.class);
        assertEquals(2, report.effectiveCases());
        assertEquals(CANDIDATE_RUN, report.candidateRunId());
        assertEquals(BASELINE_RUN, report.baselineRunId());
        assertEquals(0.5d, report.candidate().hitRate(), 1e-9d);
        assertEquals(0.5d, report.baseline().hitRate(), 1e-9d);
    }

    @Test
    void shouldBlockAndNotReleaseWhenTheCandidateRegressesOnTheIntersection() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        // Candidate misses both cases the baseline answered.
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", false, 0, 1), result("c2", false, 0, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.BLOCKED, decision.verdict());
        assertEquals(GateReason.METRICS_REGRESSED, decision.reason());
        assertEquals(-1.0d, decision.hitRateDelta(), 1e-9d);
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRecomputeOnTheIntersectionRatherThanOnEachRunsOwnCaseSet() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        // The candidate additionally judged c3 (a hit) which the baseline dropped: on its own set the candidate
        // scores 2/3 while the baseline scores 1/2, and only the intersection makes them comparable at 1/2 each.
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", false, 0, 1), result("c3", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", false, 0, 1)));

        service.runGate(VERSION_ID);

        ArgumentCaptor<String> reportCaptor = ArgumentCaptor.forClass(String.class);
        verify(appVersionService).recordGate(any(), any(), any(), any(), reportCaptor.capture(), anyString());
        GateReport report = JsonUtil.parse(reportCaptor.getValue(), GateReport.class);
        assertEquals(2, report.effectiveCases());
        assertEquals(0.5d, report.candidate().hitRate(), 1e-9d);
        assertEquals(0.5d, report.baseline().hitRate(), 1e-9d);
        assertEquals(List.of("c1", "c2"), report.caseIds());
    }

    /**
     * A release that starts a gate must hand it over and return, leaving the console a {@code GATING}
     * version to poll rather than a request blocked for the whole dual run.
     *
     * <p>Regression guard for the {@code @Async} self invocation this replaced: {@code release} called the
     * annotated method on its own bean, which the proxy never intercepts, so both evaluation runs and the
     * polling loop that waits for them ran on the releasing HTTP thread while the gate pool stood idle.
     */
    @Test
    void shouldRunTheGateOnTheGateExecutorRatherThanTheReleasingThread() throws InterruptedException {
        AppVersion version = versionOf(AppVersionStatus.TESTING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        CountDownLatch gated = new CountDownLatch(1);
        AtomicReference<String> gateThread = new AtomicReference<>();
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false))).thenAnswer(invocation -> {
            // The dual run is submitted from runGate and nowhere else, so this is the gate's own thread.
            gateThread.set(Thread.currentThread().getName());
            gated.countDown();
            return List.of(runOf(CANDIDATE_RUN, RunStatus.SUCCESS), runOf(BASELINE_RUN, RunStatus.SUCCESS));
        });

        ExecutorService gatePool = Executors.newSingleThreadExecutor(task -> new Thread(task, GATE_PROBE_THREAD));
        try {
            ReleaseGateService asyncService = newService(gatePool);

            asyncService.release(VERSION_ID, false, "admin");

            assertTrue(gated.await(HAND_OFF_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the gate never reached its executor");
            assertEquals(GATE_PROBE_THREAD, gateThread.get());
            assertNotEquals(Thread.currentThread().getName(), gateThread.get());
        } finally {
            gatePool.shutdownNow();
        }
    }

    /**
     * A gate the executor refuses must not leave the version gating forever.
     *
     * <p>{@code release} marks the version {@code GATING} and only then hands the gate over, and
     * {@code GATING} is one of the states {@code release} itself refuses to release out of. A rejection
     * that only got logged therefore locked that version out of every release path there is - not until a
     * retry, but permanently, recoverable only by editing the row. The rejection is recorded as the same
     * retryable outcome a gate that threw produces.
     */
    @Test
    void shouldRecordARetryableOutcomeWhenTheGateExecutorRefusesTheDualRun() {
        AppVersion version = versionOf(AppVersionStatus.TESTING, DATASET_ID);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        // The real markGating persists GATING, and failGate only records over a version still in it.
        doAnswer(invocation -> {
            version.setStatus(AppVersionStatus.GATING);
            return null;
        }).when(appVersionService).markGating(version);

        ReleaseGateService saturatedService = newService(SATURATED_EXECUTOR);

        saturatedService.release(VERSION_ID, false, "admin");

        verify(appVersionService).recordGate(eq(version), eq(AppVersionStatus.GATE_LOG_ONLY),
                eq(GateVerdict.LOG_ONLY), eq(GateReason.RUN_FAILED), anyString(), eq(null));
        verify(evalRunService, never()).submit(anyString(), anyInt(), anyList(), anyBoolean());
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
    }

    /**
     * A dual run that outlasts its budget records a retryable outcome rather than deciding on runs that
     * never finished.
     *
     * <p>A failure path that did not exist before the runner was made genuinely asynchronous: while the
     * runs executed inline inside {@code submit}, the polling loop saw a terminal state on its very first
     * pass and the budget was unreachable. Now that a run really queues, a saturated evaluation pool can
     * hold both sides past the deadline, and what the gate does then has to be pinned down - it must not
     * read metrics off unfinished runs, and it must not block the version on the absence of them.
     */
    @Test
    void shouldRecordLogOnlyWhenTheDualRunOutlastsItsBudget() {
        // Zero budget: the deadline has already passed when the first poll finds both sides unfinished.
        properties.getGate().setRunTimeoutMs(0L);
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        AppVersion baseline = baselineVersion();
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.parseConfig(baseline)).thenReturn(snapshot());
        when(appVersionService.currentReleased(APP_ID)).thenReturn(baseline);
        EvalRun candidateRun = unfinishedRun(CANDIDATE_RUN, RunStatus.RUNNING);
        EvalRun baselineRun = unfinishedRun(BASELINE_RUN, RunStatus.PENDING);
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false)))
                .thenReturn(List.of(candidateRun, baselineRun));
        when(evalRunService.requireRun(CANDIDATE_RUN)).thenReturn(candidateRun);
        when(evalRunService.requireRun(BASELINE_RUN)).thenReturn(baselineRun);

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.RUN_FAILED, decision.reason());
        verify(appVersionService).recordGate(any(), eq(AppVersionStatus.GATE_LOG_ONLY), eq(GateVerdict.LOG_ONLY),
                eq(GateReason.RUN_FAILED), anyString(), anyString());
        // Half written per case rows of a run still in flight are not a comparison, and reading them would
        // turn an inconclusive gate into a confident wrong number.
        verify(evalResultMapper, never()).selectList(any());
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRecordLogOnlyWhenARunOfTheDualRunFailed() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        AppVersion baseline = baselineVersion();
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.parseConfig(baseline)).thenReturn(snapshot());
        when(appVersionService.currentReleased(APP_ID)).thenReturn(baseline);
        EvalRun candidateRun = runOf(CANDIDATE_RUN, RunStatus.FAILED);
        EvalRun baselineRun = runOf(BASELINE_RUN, RunStatus.SUCCESS);
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false)))
                .thenReturn(List.of(candidateRun, baselineRun));
        when(evalRunService.requireRun(CANDIDATE_RUN)).thenReturn(candidateRun);
        when(evalRunService.requireRun(BASELINE_RUN)).thenReturn(baselineRun);

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.RUN_FAILED, decision.reason());
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any(), any());
    }

    @Test
    void shouldRecordLogOnlyWhenTheTwoRunsAreNotComparable() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        when(evalRunService.compare(anyList()))
                .thenReturn(new EvalRunService.CompareResult(false, "corpus moved", List.of()));
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.NOT_COMPARABLE, decision.reason());
    }

    @Test
    void shouldRecordTheBaselineAndReleaseOnAFirstReleaseWithASingleRun() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.currentReleased(APP_ID)).thenReturn(null);
        EvalRun candidateRun = runOf(CANDIDATE_RUN, RunStatus.SUCCESS);
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false)))
                .thenReturn(List.of(candidateRun));
        when(evalRunService.requireRun(CANDIDATE_RUN)).thenReturn(candidateRun);
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", false, 0, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.PASSED, decision.verdict());
        assertEquals(GateReason.BASELINE_RECORDED, decision.reason());
        verify(appVersionService).promote(VERSION_ID, false, null, RELEASE_SNAPSHOT);
        // Only one run is submitted when there is nothing to compare against.
        ArgumentCaptor<List<EvalRetrievalConfig>> configs = configCaptor();
        verify(evalRunService).submit(eq(DATASET_ID), anyInt(), configs.capture(), eq(false));
        assertEquals(1, configs.getValue().size());
    }

    @Test
    void shouldRunBothSidesAsBm25OnlyInAZeroKeyDeployment() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        service.runGate(VERSION_ID);

        ArgumentCaptor<List<EvalRetrievalConfig>> configs = configCaptor();
        verify(evalRunService).submit(eq(DATASET_ID), anyInt(), configs.capture(), eq(false));
        // Asking for a vector mode without an embedding provider would make the runner fail the run fast and
        // leave the gate with nothing to compare; both sides therefore run the mode the deployment can serve.
        assertEquals(2, configs.getValue().size());
        for (EvalRetrievalConfig config : configs.getValue()) {
            assertEquals(EvalMode.BM25_ONLY, config.getMode());
        }
    }

    @Test
    void shouldRequestRerankForBothSidesWhenEveryModelIsConfigured() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        when(embeddingProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        service.runGate(VERSION_ID);

        ArgumentCaptor<List<EvalRetrievalConfig>> configs = configCaptor();
        verify(evalRunService).submit(eq(DATASET_ID), anyInt(), configs.capture(), eq(false));
        for (EvalRetrievalConfig config : configs.getValue()) {
            assertEquals(EvalMode.HYBRID_RERANK, config.getMode());
        }
    }

    @Test
    void shouldRecordLogOnlyWhenTheDataSetHasTooFewEffectiveCases() {
        properties.getGate().setMinCases(50);
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1)))
                .thenReturn(List.of(result("c1", false, 0, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.INSUFFICIENT_CASES, decision.reason());
    }

    @Test
    void shouldRecordLogOnlyWhenTheStaleRatioOfTheRunExceedsTheThreshold() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        AppVersion baseline = baselineVersion();
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.parseConfig(baseline)).thenReturn(snapshot());
        when(appVersionService.currentReleased(APP_ID)).thenReturn(baseline);
        EvalRun candidateRun = runOf(CANDIDATE_RUN, RunStatus.SUCCESS);
        candidateRun.setCaseTotal(10);
        candidateRun.setCaseStale(3);
        EvalRun baselineRun = runOf(BASELINE_RUN, RunStatus.SUCCESS);
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false)))
                .thenReturn(List.of(candidateRun, baselineRun));
        when(evalRunService.requireRun(CANDIDATE_RUN)).thenReturn(candidateRun);
        when(evalRunService.requireRun(BASELINE_RUN)).thenReturn(baselineRun);
        when(evalRunService.compare(anyList()))
                .thenReturn(new EvalRunService.CompareResult(true, null, List.of()));
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.STALE_RATIO_EXCEEDED, decision.reason());
    }

    @Test
    void shouldRecordLogOnlyWhenACaseStayedDegradedOnEitherSide() {
        AppVersion version = versionOf(AppVersionStatus.GATING, DATASET_ID);
        stubDualRun(version, baselineVersion());
        EvalResult degraded = result("c2", true, 1, 1);
        degraded.setDegraded(JsonUtil.toJson(List.of("rerank_timeout")));
        when(evalResultMapper.selectList(any()))
                .thenReturn(List.of(result("c1", true, 1, 1), degraded))
                .thenReturn(List.of(result("c1", true, 1, 1), result("c2", true, 1, 1)));

        GateDecision decision = service.runGate(VERSION_ID);

        assertEquals(GateVerdict.LOG_ONLY, decision.verdict());
        assertEquals(GateReason.DEGRADED_CASES, decision.reason());
    }

    private void stubDualRun(AppVersion version, AppVersion baseline) {
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        when(appVersionService.parseConfig(baseline)).thenReturn(snapshot());
        when(appVersionService.currentReleased(APP_ID)).thenReturn(baseline);
        EvalRun candidateRun = runOf(CANDIDATE_RUN, RunStatus.SUCCESS);
        EvalRun baselineRun = runOf(BASELINE_RUN, RunStatus.SUCCESS);
        when(evalRunService.submit(eq(DATASET_ID), anyInt(), anyList(), eq(false)))
                .thenReturn(List.of(candidateRun, baselineRun));
        when(evalRunService.requireRun(CANDIDATE_RUN)).thenReturn(candidateRun);
        when(evalRunService.requireRun(BASELINE_RUN)).thenReturn(baselineRun);
        when(evalRunService.compare(anyList()))
                .thenReturn(new EvalRunService.CompareResult(true, null, List.of()));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<EvalRetrievalConfig>> configCaptor() {
        return ArgumentCaptor.forClass((Class<List<EvalRetrievalConfig>>) (Class<?>) List.class);
    }

    private AppVersion versionOf(AppVersionStatus status, String datasetId) {
        AppVersion version = new AppVersion();
        version.setAppVersionId(VERSION_ID);
        version.setAppId(APP_ID);
        version.setVersion("V2.0");
        version.setStatus(status);
        version.setGateDatasetId(datasetId);
        version.setForceReleased(0);
        return version;
    }

    private AppVersion baselineVersion() {
        AppVersion baseline = new AppVersion();
        baseline.setAppVersionId(BASELINE_VERSION_ID);
        baseline.setAppId(APP_ID);
        baseline.setVersion("V1.0");
        baseline.setStatus(AppVersionStatus.RELEASED);
        return baseline;
    }

    private AppConfigSnapshot snapshot() {
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbId("kb_1");
        KbRetrievalConfig retrieval = new KbRetrievalConfig();
        retrieval.setTopN(5);
        retrieval.setRecallTopK(50);
        retrieval.setFusionMode("rrf");
        retrieval.setRerankEnabled(true);
        snapshot.setRetrieval(retrieval);
        return snapshot;
    }

    /**
     * A run as the polling loop finds it while it is still queued or executing: no counts yet, because the
     * runner writes them once, at the end.
     *
     * @param runId  run business id
     * @param status non terminal status
     * @return unfinished run
     */
    private EvalRun unfinishedRun(String runId, RunStatus status) {
        EvalRun run = new EvalRun();
        run.setRunId(runId);
        run.setDatasetId(DATASET_ID);
        run.setStatus(status);
        run.setCaseTotal(0);
        run.setCaseStale(0);
        run.setCaseEffective(0);
        run.setCaseDegraded(0);
        return run;
    }

    private EvalRun runOf(String runId, RunStatus status) {
        EvalRun run = new EvalRun();
        run.setRunId(runId);
        run.setDatasetId(DATASET_ID);
        run.setStatus(status);
        run.setCaseTotal(2);
        run.setCaseStale(0);
        run.setCaseEffective(2);
        run.setCaseDegraded(0);
        return run;
    }

    private EvalResult result(String caseId, boolean hit, int evidenceHits, int evidenceTotal) {
        EvalResult result = new EvalResult();
        result.setCaseId(caseId);
        result.setHit(hit ? 1 : 0);
        result.setEvidenceHitCount(evidenceHits);
        result.setEvidenceTotalCount(evidenceTotal);
        result.setDegraded(JsonUtil.toJson(new ArrayList<String>()));
        return result;
    }
}
