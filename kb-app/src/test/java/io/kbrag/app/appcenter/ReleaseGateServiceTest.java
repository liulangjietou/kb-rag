package io.kbrag.app.appcenter;

import io.kbrag.app.eval.EvalRunService;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private AppVersionService appVersionService;
    private EvalRunService evalRunService;
    private EvalResultMapper evalResultMapper;
    private EmbeddingProvider embeddingProvider;
    private RerankProvider rerankProvider;
    private KbProperties properties;
    private ReleaseGateService service;

    @BeforeEach
    void setUp() {
        appVersionService = mock(AppVersionService.class);
        evalRunService = mock(EvalRunService.class);
        evalResultMapper = mock(EvalResultMapper.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        rerankProvider = mock(RerankProvider.class);
        properties = new KbProperties();
        properties.getGate().setMinCases(2);
        service = new ReleaseGateService(appVersionService, evalRunService, evalResultMapper,
                new GateMetricsRecomputer(), new ReleaseGateJudge(), embeddingProvider, rerankProvider,
                properties);
    }

    @Test
    void shouldRecordAGateLogOnlyOutcomeWhenNoDataSetIsBoundAndNotRelease() {
        AppVersion version = versionOf(AppVersionStatus.TESTING, null);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());

        service.release(VERSION_ID, false, "admin");

        verify(appVersionService).recordGate(eq(version), eq(AppVersionStatus.GATE_LOG_ONLY),
                eq(GateVerdict.LOG_ONLY), eq(GateReason.NO_DATASET), anyString(), eq(null));
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), anyString());
    }

    @Test
    void shouldReleaseAnUngatedVersionOnlyWhenTheCallerForcesIt() {
        AppVersion version = versionOf(AppVersionStatus.TESTING, null);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());

        service.release(VERSION_ID, true, "admin");

        verify(appVersionService).recordGate(eq(version), eq(AppVersionStatus.GATE_LOG_ONLY),
                eq(GateVerdict.LOG_ONLY), eq(GateReason.NO_DATASET), anyString(), eq(null));
        verify(appVersionService).promote(VERSION_ID, true, "admin");
    }

    @Test
    void shouldPromoteAPassedVersionWithoutForcingAndWithoutReGating() {
        AppVersion version = versionOf(AppVersionStatus.GATE_PASSED, DATASET_ID);
        version.setGateVerdict(GateVerdict.PASSED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);

        service.release(VERSION_ID, false, "admin");

        verify(appVersionService).promote(VERSION_ID, false, "admin");
        verify(evalRunService, never()).submit(anyString(), anyInt(), anyList(), anyBoolean());
    }

    @Test
    void shouldRecordAForcedReleaseOverABlockingVerdictWithoutReRunningTheGate() {
        AppVersion version = versionOf(AppVersionStatus.GATE_BLOCKED, DATASET_ID);
        version.setGateVerdict(GateVerdict.BLOCKED);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);

        service.release(VERSION_ID, true, "admin");

        // Forcing overrides a known verdict; re-running the comparison would replace the very evidence the
        // override is recorded against.
        verify(appVersionService).promote(VERSION_ID, true, "admin");
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
        verify(appVersionService).promote(VERSION_ID, false, null);

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
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any());
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
        verify(appVersionService, never()).promote(anyString(), anyBoolean(), any());
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
        verify(appVersionService).promote(VERSION_ID, false, null);
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
