package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.config.AsyncConfig;
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
import io.kbrag.domain.model.GateCaseOutcome;
import io.kbrag.domain.model.GateComparison;
import io.kbrag.domain.model.GateDecision;
import io.kbrag.domain.model.GateReport;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.service.GateMetricsRecomputer;
import io.kbrag.domain.service.ReleaseGateJudge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the release gate and drives the release itself, requirement section 4.7 "evaluation gate".
 *
 * <p><b>Same corpus, same moment, two configurations.</b> The gate submits two evaluation runs through
 * {@link EvalRunService} - the candidate configuration and the currently released one - against the same
 * data set at the same time. Comparing against stored historical numbers instead would drift with the
 * corpus and the data set, which is exactly the false positive the requirement forbids.
 *
 * <p><b>The runner is reused, never reimplemented.</b> Both sides go through the same offline evaluation
 * path the console's own reports use, so a gate verdict measures the retrieval behaviour production traffic
 * would see. A second, gate specific retrieval loop would drift from the served one and make a passing gate
 * meaningless.
 *
 * <p><b>Asynchronous with a synchronous shortcut.</b> A version with no data set bound is decided inline and
 * the caller gets its verdict immediately; a version with a data set enters {@code GATING} and the dual run
 * proceeds on its own executor, which is what the console's progress display exists for.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseGateService {

    private static final String CANDIDATE_LABEL = "候选配置";
    private static final String BASELINE_LABEL = "当前正式版配置";
    private static final int HIT = 1;

    private final AppVersionService appVersionService;
    private final AppReleaseSnapshotService releaseSnapshotService;
    private final EvalRunService evalRunService;
    private final EvalResultMapper evalResultMapper;
    private final GateMetricsRecomputer gateMetricsRecomputer;
    private final ReleaseGateJudge releaseGateJudge;
    private final EmbeddingProvider embeddingProvider;
    private final RerankProvider rerankProvider;
    private final KbProperties properties;

    /**
     * Releases a version, running the gate first when one applies.
     *
     * <p>Four outcomes, all of them legal answers to the same request: released after a passing gate,
     * released because an operator forced it past a non passing verdict, left in {@code GATING} while the
     * dual run proceeds, or left in a gate outcome state awaiting a decision.
     *
     * @param appVersionId version business id
     * @param force        {@code true} releases despite a non passing verdict and records who did it
     * @param operator     authenticated console user, recorded with a forced release
     * @return the version as it stands after this call
     */
    public AppVersion release(String appVersionId, boolean force, String operator) {
        AppVersion version = appVersionService.require(appVersionId);
        if (version.getStatus() == AppVersionStatus.RELEASED) {
            throw BizException.invalidParam("该版本已经是当前正式版");
        }
        if (version.getStatus() == AppVersionStatus.SUPERSEDED) {
            throw BizException.invalidParam("已下线版本请使用回滚接口重新发布");
        }
        if (version.getStatus() == AppVersionStatus.GATING) {
            throw BizException.invalidParam("门禁评测正在进行中，请等待完成后再发布");
        }
        if (version.getStatus() == AppVersionStatus.DRAFT) {
            throw BizException.invalidParam("草稿版本需先提交测试版后才能发布");
        }

        // A version whose gate already produced a verdict is not re-gated by a forced release: forcing is an
        // operator overriding a known verdict, and re-running the comparison would replace the very evidence
        // the override is recorded against.
        if (force && version.getStatus().gateOutcome()) {
            return promoteWithSnapshot(appVersionId, !isPassed(version), operator);
        }
        if (version.getStatus() == AppVersionStatus.GATE_PASSED) {
            return promoteWithSnapshot(appVersionId, false, operator);
        }
        if (version.getGateDatasetId() == null || version.getGateDatasetId().isBlank()) {
            GateDecision decision = releaseGateJudge.judge(new ReleaseGateJudge.GateInput(
                    false, false, false, null, 0.0d, null,
                    properties.getGate().getMinCases(), properties.getGate().getEpsilonPp(),
                    properties.getGate().getStaleRatio()));
            appVersionService.recordGate(version, decision.verdict().status(), decision.verdict(),
                    decision.reason(), JsonUtil.toJson(reportOf(decision, null, 0, 0, 0.0d, null, null)), null);
            if (force) {
                return promoteWithSnapshot(appVersionId, true, operator);
            }
            log.info("release requires an explicit forced release, appVersionId={}, reason={}",
                    appVersionId, decision.reason());
            return version;
        }
        appVersionService.markGating(version);
        runGateAsync(appVersionId);
        return appVersionService.require(appVersionId);
    }

    /**
     * Freezes the index snapshot of a release and only then moves the version to {@code RELEASED},
     * requirement section 4.7 "the snapshot is created after the gate and before the version takes effect".
     *
     * <p><b>The single door to a release.</b> Every path that promotes a version forward goes through here -
     * a passing gate, a forced release over a verdict, a release with no data set bound - so no route can
     * publish a version without a snapshot. A rollback deliberately does not: it reinstates a version that
     * already owns one.
     *
     * <p><b>Failure leaves the version exactly where it was.</b> The snapshot is built before the state
     * transition, so a copy that fails aborts before anything is written and the version stays in its gate
     * outcome state, retryable. Should the transition itself fail afterwards - the released slot taken by a
     * concurrent release, for instance - the indices just created are deleted again, because a snapshot no
     * version references is dead storage that nothing would ever clean up.
     *
     * @param appVersionId version business id
     * @param forced       {@code true} records a release over a non passing verdict
     * @param operator     who performed the release
     * @return promoted version
     */
    private AppVersion promoteWithSnapshot(String appVersionId, boolean forced, String operator) {
        AppVersion version = appVersionService.require(appVersionId);
        AppReleaseSnapshotService.ReleaseSnapshot snapshot =
                releaseSnapshotService.freeze(version, appVersionService.parseConfig(version));
        try {
            return appVersionService.promote(appVersionId, forced, operator, snapshot);
        } catch (Exception e) {
            releaseSnapshotService.discard(snapshot);
            throw e;
        }
    }

    /**
     * Runs one gate off the calling request.
     *
     * <p>Its own executor and not the evaluation pool: this thread waits for evaluation runs to finish, and
     * waiting inside the pool those runs are queued on would deadlock the gate against its own work.
     *
     * @param appVersionId version business id
     */
    @Async(AsyncConfig.GATE_EXECUTOR)
    public void runGateAsync(String appVersionId) {
        try {
            runGate(appVersionId);
        } catch (Exception e) {
            log.error("release gate pass failed, errorCode={}, appVersionId={}",
                    ErrorCode.INTERNAL_ERROR, appVersionId, e);
            failGate(appVersionId);
        }
    }

    /**
     * Executes the dual run, recomputes both sides on the intersection and records the verdict.
     *
     * @param appVersionId version business id
     * @return decision that was recorded
     */
    public GateDecision runGate(String appVersionId) {
        AppVersion version = appVersionService.require(appVersionId);
        AppConfigSnapshot candidateConfig = appVersionService.parseConfig(version);
        AppVersion baselineVersion = appVersionService.currentReleased(version.getAppId());
        String datasetId = version.getGateDatasetId();

        List<EvalRetrievalConfig> configs = new ArrayList<>(2);
        configs.add(toEvalConfig(CANDIDATE_LABEL, candidateConfig));
        if (baselineVersion != null) {
            configs.add(toEvalConfig(BASELINE_LABEL, appVersionService.parseConfig(baselineVersion)));
        }
        int k = candidateConfig.retrievalOrDefaults().getTopN() == null
                ? properties.getRetrieval().getDefaultTopN()
                : candidateConfig.retrievalOrDefaults().getTopN();

        List<EvalRun> runs = evalRunService.submit(datasetId, k, configs, false);
        List<EvalRun> finished = awaitCompletion(runs);
        EvalRun candidateRun = finished.get(0);
        EvalRun baselineRun = finished.size() > 1 ? finished.get(1) : null;

        boolean succeeded = candidateRun.getStatus() == RunStatus.SUCCESS
                && (baselineRun == null || baselineRun.getStatus() == RunStatus.SUCCESS);
        boolean comparable = baselineRun == null || succeeded && isComparable(candidateRun, baselineRun);
        GateComparison comparison = succeeded
                ? gateMetricsRecomputer.recompute(caseOutcomesOf(candidateRun),
                        baselineRun == null ? List.of() : caseOutcomesOf(baselineRun))
                : null;

        double staleRatio = staleRatioOf(candidateRun);
        GateDecision decision = releaseGateJudge.judge(new ReleaseGateJudge.GateInput(
                true, succeeded, comparable, comparison, staleRatio, candidateConfig.getGate(),
                properties.getGate().getMinCases(), properties.getGate().getEpsilonPp(),
                properties.getGate().getStaleRatio()));

        GateReport report = reportOf(decision, comparison, candidateRun.getCaseTotal(),
                candidateRun.getCaseStale(), staleRatio, candidateRun.getRunId(),
                baselineRun == null ? null : baselineRun.getRunId());
        // Run id order is part of the contract with the console: candidate first, baseline second, so the
        // comparison panel can reuse the M4b compare endpoint without a second convention.
        List<String> runIds = new ArrayList<>();
        runIds.add(candidateRun.getRunId());
        if (baselineRun != null) {
            runIds.add(baselineRun.getRunId());
        }
        appVersionService.recordGate(appVersionService.require(appVersionId), decision.verdict().status(),
                decision.verdict(), decision.reason(), JsonUtil.toJson(report), JsonUtil.toJson(runIds));

        if (decision.verdict().autoReleasable()) {
            try {
                promoteWithSnapshot(appVersionId, false, null);
            } catch (Exception e) {
                // The verdict is already recorded and the version is sitting in GATE_PASSED, which is exactly
                // the retryable state a failed snapshot is supposed to leave behind. Rethrowing would only let
                // the async wrapper try to record a gate failure over a gate that in fact succeeded.
                log.error("release blocked by a failed index snapshot, errorCode={}, appVersionId={}",
                        ErrorCode.INTERNAL_ERROR, appVersionId, e);
            }
        }
        log.info("release gate finished, appVersionId={}, verdict={}, reason={}, effectiveCases={}, epsilon={}",
                appVersionId, decision.verdict(), decision.reason(),
                comparison == null ? 0 : comparison.effectiveCases(), decision.epsilon());
        return decision;
    }

    /**
     * Maps a frozen application configuration onto the evaluation runner's configuration row.
     *
     * <p><b>The mode is derived from what the deployment can actually do</b>, not from what the snapshot
     * wishes for. A zero key deployment has no embedding provider, so both sides run BM25 only - which is
     * still a valid comparison, because the two sides run the same mode against the same corpus. Asking for a
     * vector mode there would make the runner fail the run fast and leave the gate with nothing to compare.
     *
     * @param label    display label of this side
     * @param snapshot frozen configuration of this side
     * @return evaluation configuration row
     */
    private EvalRetrievalConfig toEvalConfig(String label, AppConfigSnapshot snapshot) {
        KbRetrievalConfig retrieval = snapshot.retrievalOrDefaults();
        EvalRetrievalConfig config = new EvalRetrievalConfig();
        config.setLabel(label);
        config.setMode(modeOf(retrieval));
        config.setRecallTopK(retrieval.getRecallTopK());
        config.setTopN(retrieval.getTopN());
        config.setFusion(retrieval.getFusionMode());
        config.setScoreThreshold(retrieval.getScoreThreshold());
        config.setRewriteEnabled(retrieval.getRewriteEnabled());
        return config;
    }

    private EvalMode modeOf(KbRetrievalConfig retrieval) {
        if (!embeddingProvider.isConfigured()) {
            return EvalMode.BM25_ONLY;
        }
        boolean rerank = !Boolean.FALSE.equals(retrieval.getRerankEnabled()) && rerankProvider.isConfigured();
        return rerank ? EvalMode.HYBRID_RERANK : EvalMode.HYBRID;
    }

    /**
     * Waits for every run of the dual run to reach a terminal state.
     *
     * <p>Polling rather than a callback: the runner already owns the execution and its own error handling, and
     * a run that fails records that fact on its row, so re-reading the rows is both the simplest and the most
     * honest way to learn the outcome. The budget bounds the wait so a stuck run leaves a retryable verdict
     * instead of an executor thread parked forever.
     *
     * @param runs runs as they were created
     * @return the same runs, re-read in the same order, in whatever state they ended up in
     */
    private List<EvalRun> awaitCompletion(List<EvalRun> runs) {
        long deadline = System.currentTimeMillis() + properties.getGate().getRunTimeoutMs();
        long interval = Math.max(1L, properties.getGate().getPollIntervalMs());
        while (true) {
            List<EvalRun> current = runs.stream()
                    .map(run -> evalRunService.requireRun(run.getRunId()))
                    .toList();
            boolean pending = current.stream()
                    .anyMatch(run -> run.getStatus() == RunStatus.PENDING || run.getStatus() == RunStatus.RUNNING);
            if (!pending) {
                return current;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("release gate dual run exceeded its budget, errorCode={}, runIds={}",
                        ErrorCode.INTERNAL_ERROR, current.stream().map(EvalRun::getRunId).toList());
                return current;
            }
            sleep(interval);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("release gate wait interrupted", e);
        }
    }

    /**
     * Reads one run's per case rows into the flat shape the intersection recomputation consumes.
     *
     * @param run finished run
     * @return per case outcomes
     */
    private List<GateCaseOutcome> caseOutcomesOf(EvalRun run) {
        List<EvalResult> results = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getRunId, run.getRunId()));
        if (CollectionUtils.isEmpty(results)) {
            return List.of();
        }
        List<GateCaseOutcome> outcomes = new ArrayList<>(results.size());
        for (EvalResult result : results) {
            outcomes.add(new GateCaseOutcome(result.getCaseId(),
                    result.getHit() != null && result.getHit() == HIT,
                    result.getEvidenceHitCount() == null ? 0 : result.getEvidenceHitCount(),
                    result.getEvidenceTotalCount() == null ? 0 : result.getEvidenceTotalCount(),
                    isDegraded(result.getDegraded())));
        }
        return outcomes;
    }

    /**
     * Tells whether a stored degradation array holds anything.
     *
     * @param degradedJson JSON array column
     * @return {@code true} when the case carried at least one marker
     */
    private boolean isDegraded(String degradedJson) {
        if (degradedJson == null || degradedJson.isBlank()) {
            return false;
        }
        List<String> markers = JsonUtil.parse(degradedJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
        return CollectionUtils.isNotEmpty(markers);
    }

    private boolean isComparable(EvalRun candidate, EvalRun baseline) {
        // Reuses the M4b comparability rule (same data set revision, same corpus fingerprint) rather than
        // restating it: the gate must not accept a pair the report page refuses to place side by side.
        return evalRunService.compare(List.of(candidate.getRunId(), baseline.getRunId())).comparable();
    }

    private double staleRatioOf(EvalRun run) {
        int total = run.getCaseTotal() == null ? 0 : run.getCaseTotal();
        int stale = run.getCaseStale() == null ? 0 : run.getCaseStale();
        return total == 0 ? 0.0d : (double) stale / total;
    }

    private boolean isPassed(AppVersion version) {
        return version.getGateVerdict() == GateVerdict.PASSED;
    }

    /**
     * Records a gate that could not run at all as a retryable outcome.
     *
     * @param appVersionId version business id
     */
    private void failGate(String appVersionId) {
        try {
            AppVersion version = appVersionService.require(appVersionId);
            if (version.getStatus() != AppVersionStatus.GATING) {
                return;
            }
            GateDecision decision = GateDecision.of(GateVerdict.LOG_ONLY, GateReason.RUN_FAILED);
            appVersionService.recordGate(version, decision.verdict().status(), decision.verdict(),
                    decision.reason(), JsonUtil.toJson(reportOf(decision, null, 0, 0, 0.0d, null, null)), null);
        } catch (Exception e) {
            log.error("release gate failure could not be recorded, errorCode={}, appVersionId={}",
                    ErrorCode.INTERNAL_ERROR, appVersionId, e);
        }
    }

    private GateReport reportOf(GateDecision decision, GateComparison comparison, Integer totalCases,
                                Integer staleCases, double staleRatio, String candidateRunId,
                                String baselineRunId) {
        return new GateReport(
                decision.verdict().name(),
                decision.reason().name(),
                decision.reason().message(),
                comparison == null ? null : comparison.candidate(),
                comparison == null ? null : comparison.baseline(),
                comparison == null ? 0 : comparison.effectiveCases(),
                totalCases == null ? 0 : totalCases,
                staleCases == null ? 0 : staleCases,
                staleRatio,
                comparison == null ? 0 : comparison.degradedCases(),
                decision.epsilon(),
                decision.hitRateDelta(),
                decision.recallDelta(),
                candidateRunId,
                baselineRunId,
                LocalDateTime.now().toString(),
                comparison == null ? List.of() : comparison.caseIds());
    }
}
