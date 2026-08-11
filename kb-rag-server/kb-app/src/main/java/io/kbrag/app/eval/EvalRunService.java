package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.retrieval.RetrievalMetadataKeys;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.OfflineExecutionContext;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.EvalMode;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.constant.EvalMetricGroups;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.CaseJudgment;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.model.EvalMetricsAtK;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.EvalHitJudge;
import io.kbrag.domain.service.EvalMetricsCalculator;
import io.kbrag.domain.service.OverlapRatioCalculator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Evaluation run submission, execution and comparison, requirement section 4.6.
 *
 * <p><b>Reuses {@link RetrievalService}, never duplicates it.</b> Every mode of the configuration
 * matrix is realised by translating {@link EvalMode} onto {@link RetrievalCommand} fields the online
 * search endpoint already understands - forcing a route off, requesting rerank - and the call itself
 * runs inside {@link OfflineExecutionContext#runOffline} so the shared pipeline picks up the offline
 * timeout profile and stays out of the production degradation monitor. A configuration matrix
 * submission therefore measures exactly the retrieval behaviour production traffic would see under
 * the same parameters, which is the entire premise the comparison is trusted for.
 *
 * <p><b>Zero key fail-fast.</b> A mode that reads the vector route without an embedding provider
 * configured is never silently downgraded to a BM25 result labelled as something else; its run is
 * created {@code FAILED} with an explicit reason and never executes, requirement section 3.1.
 *
 * <p><b>The executors are injected, not annotated.</b> A submission hands its runs over from
 * {@link #createOne}, which is this very bean calling itself - the shape a proxied {@code @Async}
 * silently runs inline, leaving the whole matrix on the submitting request thread and the pool idle.
 * Handing the task to the executor directly is the same choice, and for the same reason, that
 * {@code IndexCleanupService} made.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class EvalRunService {

    private static final int MIN_CONFIGS = 1;
    private static final int MAX_CONFIGS = 6;
    private static final int MIN_K = 1;

    private final EvalDatasetService evalDatasetService;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final RetrievalService retrievalService;
    private final EmbeddingProvider embeddingProvider;
    private final RerankProvider rerankProvider;
    private final ChatProvider chatProvider;
    private final EvalJudgeService evalJudgeService;
    private final CorpusFingerprintFactory corpusFingerprintFactory;
    private final EvalHitJudge evalHitJudge;
    private final EvalMetricsCalculator evalMetricsCalculator;
    private final OverlapRatioCalculator overlapRatioCalculator;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;
    private final Executor evalExecutor;
    private final Executor evalCaseExecutor;

    public EvalRunService(EvalDatasetService evalDatasetService,
                          EvalRunMapper evalRunMapper,
                          EvalResultMapper evalResultMapper,
                          EvalCaseMapper evalCaseMapper,
                          RetrievalService retrievalService,
                          EmbeddingProvider embeddingProvider,
                          RerankProvider rerankProvider,
                          ChatProvider chatProvider,
                          EvalJudgeService evalJudgeService,
                          CorpusFingerprintFactory corpusFingerprintFactory,
                          EvalHitJudge evalHitJudge,
                          EvalMetricsCalculator evalMetricsCalculator,
                          OverlapRatioCalculator overlapRatioCalculator,
                          BizIdGenerator bizIdGenerator,
                          KbProperties properties,
                          @Qualifier(AsyncConfig.EVAL_EXECUTOR) Executor evalExecutor,
                          @Qualifier(AsyncConfig.EVAL_CASE_EXECUTOR) Executor evalCaseExecutor) {
        this.evalDatasetService = evalDatasetService;
        this.evalRunMapper = evalRunMapper;
        this.evalResultMapper = evalResultMapper;
        this.evalCaseMapper = evalCaseMapper;
        this.retrievalService = retrievalService;
        this.embeddingProvider = embeddingProvider;
        this.rerankProvider = rerankProvider;
        this.chatProvider = chatProvider;
        this.evalJudgeService = evalJudgeService;
        this.corpusFingerprintFactory = corpusFingerprintFactory;
        this.evalHitJudge = evalHitJudge;
        this.evalMetricsCalculator = evalMetricsCalculator;
        this.overlapRatioCalculator = overlapRatioCalculator;
        this.bizIdGenerator = bizIdGenerator;
        this.properties = properties;
        this.evalExecutor = evalExecutor;
        this.evalCaseExecutor = evalCaseExecutor;
    }

    /**
     * Creates one run per configuration of the matrix, kicking off execution for every one that is not
     * failed fast.
     *
     * @param datasetId    data set business id
     * @param k            metrics {@code K}, the default {@code top_n} of every configuration that does
     *                     not override it
     * @param configs      1 to 6 configurations to compare
     * @param judgeEnabled {@code true} runs the LLM-as-judge stage alongside every case
     * @return created runs, one per configuration, in submission order
     */
    public List<EvalRun> submit(String datasetId, int k, List<EvalRetrievalConfig> configs, boolean judgeEnabled) {
        validateSubmission(k, configs);
        EvalDataset dataset = evalDatasetService.require(datasetId);
        String corpusFingerprint = corpusFingerprintFactory.fingerprint(dataset.getKbId());
        List<EvalRun> created = new ArrayList<>(configs.size());
        for (EvalRetrievalConfig config : configs) {
            created.add(createOne(dataset, corpusFingerprint, k, config, judgeEnabled));
        }
        return created;
    }

    /**
     * Estimates the model calls one submission would issue, requirement section 4.6 "show the
     * estimated call count before submission".
     *
     * @param datasetId    data set business id
     * @param k            metrics {@code K}
     * @param configs      configuration matrix under consideration
     * @param judgeEnabled {@code true} counts the judge stage too
     * @return predicted call counts by capability
     */
    public EstimateResult estimate(String datasetId, int k, List<EvalRetrievalConfig> configs, boolean judgeEnabled) {
        validateSubmission(k, configs);
        EvalDataset dataset = evalDatasetService.require(datasetId);
        List<EvalCase> cases = evalCaseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, dataset.getDatasetId())
                .eq(EvalCase::getStatus, CaseStatus.ACTIVE));
        int caseCount = cases.size();
        long judgeableCases = cases.stream()
                .filter(c -> c.getExpectedAnswer() != null && !c.getExpectedAnswer().isBlank())
                .count();

        long embeddingCalls = 0;
        long rerankCalls = 0;
        long rewriteCalls = 0;
        long judgeCalls = 0;
        for (EvalRetrievalConfig config : configs) {
            EvalMode mode = config.getMode();
            boolean willExecute = !(mode.requiresVector() && !embeddingProvider.isConfigured());
            if (!willExecute) {
                continue;
            }
            if (mode.requiresVector()) {
                embeddingCalls += caseCount;
            }
            if (mode.rerankRequested() && rerankProvider.isConfigured()) {
                rerankCalls += caseCount;
            }
            if (Boolean.TRUE.equals(config.getRewriteEnabled()) && chatProvider.isConfigured()) {
                rewriteCalls += caseCount;
            }
            if (judgeEnabled && evalJudgeService.isAvailable()) {
                judgeCalls += judgeableCases;
            }
        }
        return new EstimateResult(embeddingCalls, rerankCalls, rewriteCalls, judgeCalls);
    }

    /**
     * Loads a run or fails.
     *
     * @param runId run business id
     * @return run
     */
    public EvalRun requireRun(String runId) {
        EvalRun run = evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getRunId, runId)
                .last("limit 1"));
        if (run == null) {
            throw BizException.notFound("evaluation run not found");
        }
        return run;
    }

    /**
     * Pages the runs of a data set, newest first.
     *
     * @param datasetId data set business id
     * @param page      one based page number
     * @param size      page size
     * @return page of runs
     */
    public IPage<EvalRun> listRuns(String datasetId, long page, long size) {
        evalDatasetService.require(datasetId);
        return evalRunMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId)
                .orderByDesc(EvalRun::getId));
    }

    /**
     * Pages the per case results of a run, requirement section 4.6 "drill down".
     *
     * @param runId run business id
     * @param hit   optional hit filter
     * @param page  one based page number
     * @param size  page size
     * @return page of results
     */
    public IPage<EvalResult> results(String runId, Boolean hit, long page, long size) {
        requireRun(runId);
        LambdaQueryWrapper<EvalResult> wrapper = new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getRunId, runId)
                .orderByDesc(EvalResult::getId);
        if (hit != null) {
            wrapper.eq(EvalResult::getHit, hit ? 1 : 0);
        }
        return evalResultMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Compares the metrics of several runs, requirement section 4.6 "corpus change must be flagged".
     *
     * @param runIds run business ids to place side by side
     * @return comparison outcome, {@code comparable=false} with a reason when the runs do not share
     *         both {@code dataset_revision} and {@code corpus_fingerprint}
     */
    public CompareResult compare(List<String> runIds) {
        if (CollectionUtils.isEmpty(runIds) || runIds.size() < 2) {
            throw BizException.invalidParam("at least two run ids are required to compare");
        }
        List<EvalRun> runs = runIds.stream().map(this::requireRun).toList();
        EvalRun first = runs.get(0);
        for (EvalRun run : runs) {
            if (!java.util.Objects.equals(run.getDatasetRevision(), first.getDatasetRevision())) {
                return CompareResult.notComparable(runs,
                        "runs were taken at different data set revisions (" + first.getDatasetRevision()
                                + " vs " + run.getDatasetRevision() + "), the case set changed between them");
            }
            if (!java.util.Objects.equals(run.getCorpusFingerprint(), first.getCorpusFingerprint())) {
                return CompareResult.notComparable(runs,
                        "runs measured different corpus states, the active version set, the embedding "
                                + "model or the ik dictionary changed between them");
            }
        }
        return CompareResult.comparable(runs);
    }

    private void validateSubmission(int k, List<EvalRetrievalConfig> configs) {
        if (k < MIN_K) {
            throw BizException.invalidParam("k must be at least 1");
        }
        if (CollectionUtils.isEmpty(configs) || configs.size() < MIN_CONFIGS || configs.size() > MAX_CONFIGS) {
            throw BizException.invalidParam("configs must contain between 1 and 6 entries");
        }
        for (EvalRetrievalConfig config : configs) {
            if (config.getMode() == null) {
                throw BizException.invalidParam("every configuration requires a mode");
            }
        }
    }

    private EvalRun createOne(EvalDataset dataset, String corpusFingerprint, int k, EvalRetrievalConfig config,
                              boolean judgeEnabled) {
        config.setTopN(config.getTopN() != null ? config.getTopN() : k);
        EvalMode mode = config.getMode();

        EvalRun run = new EvalRun();
        run.setRunId(bizIdGenerator.evalRunId());
        run.setDatasetId(dataset.getDatasetId());
        run.setKbId(dataset.getKbId());
        run.setDatasetRevision(dataset.getDatasetRevision());
        run.setCorpusFingerprint(corpusFingerprint);
        run.setRetrievalConfig(JsonUtil.toJson(config));
        if (judgeEnabled) {
            run.setJudgeModel(evalJudgeService.model());
            run.setJudgePromptVersion(EvalJudgeService.JUDGE_PROMPT_VERSION);
        }
        run.setCaseTotal(0);
        run.setCaseEffective(0);
        run.setCaseStale(0);
        run.setCaseDegraded(0);

        if (mode.requiresVector() && !embeddingProvider.isConfigured()) {
            run.setStatus(RunStatus.FAILED);
            run.setFailReason("嵌入模型未配置：" + mode.name() + " 模式需要向量检索，当前部署未配置嵌入模型，"
                    + "为避免产出误导性的混合检索指标，本次运行直接判定失败");
            run.setFinishedAt(LocalDateTime.now());
            evalRunMapper.insert(run);
            log.info("evaluation run failed fast for a vector dependent mode with no embedding provider, "
                    + "runId={}, mode={}", run.getRunId(), mode);
            return run;
        }
        run.setStatus(RunStatus.PENDING);
        evalRunMapper.insert(run);
        submitExecution(run.getRunId(), judgeEnabled);
        return run;
    }

    /**
     * Queues one run's execution off the submitting request.
     *
     * <p>The run row is already {@code PENDING} in the database when this returns, so a submission that
     * never reaches its executor thread is visible as a run that stayed pending rather than as one that
     * was never created - which is what the release gate's wait budget is there to time out on.
     *
     * @param runId        run business id
     * @param judgeEnabled {@code true} runs the LLM-as-judge stage
     */
    private void submitExecution(String runId, boolean judgeEnabled) {
        evalExecutor.execute(() -> {
            try {
                execute(runId, judgeEnabled);
            } catch (Exception e) {
                log.error("evaluation run execution could not start, errorCode={}, runId={}",
                        ErrorCode.INTERNAL_ERROR, runId, e);
            }
        });
    }

    /**
     * Runs one evaluation synchronously; every failure is translated into a persisted {@code FAILED}
     * state instead of propagating, since the caller is an executor thread with nobody to report to.
     *
     * @param runId        run business id
     * @param judgeEnabled {@code true} runs the LLM-as-judge stage
     */
    public void execute(String runId, boolean judgeEnabled) {
        EvalRun run = requireRun(runId);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        evalRunMapper.updateById(run);
        try {
            List<EvalCase> allCases = evalCaseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                    .eq(EvalCase::getDatasetId, run.getDatasetId())
                    .ne(EvalCase::getStatus, CaseStatus.DEPRECATED));
            List<EvalCase> effective = allCases.stream()
                    .filter(c -> c.getStatus() == CaseStatus.ACTIVE)
                    .toList();
            EvalRetrievalConfig config = JsonUtil.parse(run.getRetrievalConfig(), EvalRetrievalConfig.class);
            int k = config.getTopN();

            List<CaseOutcome> outcomes = judgeAll(run.getKbId(), effective, config, k, judgeEnabled);
            for (CaseOutcome outcome : outcomes) {
                evalResultMapper.insert(toEntity(runId, outcome));
            }

            Map<String, Map<String, EvalMetricsAtK>> metrics = buildMetrics(outcomes, allCases, k);
            long degradedCount = outcomes.stream().filter(o -> !o.degraded().isEmpty()).count();

            run.setStatus(RunStatus.SUCCESS);
            run.setMetrics(JsonUtil.toJson(metrics));
            run.setCaseTotal(allCases.size());
            run.setCaseEffective(effective.size());
            run.setCaseStale(allCases.size() - effective.size());
            run.setCaseDegraded((int) degradedCount);
            run.setFinishedAt(LocalDateTime.now());
            evalRunMapper.updateById(run);
            log.info("evaluation run finished, runId={}, cases={}, degraded={}",
                    runId, effective.size(), degradedCount);
        } catch (Exception e) {
            run.setStatus(RunStatus.FAILED);
            run.setFailReason(truncate(e.getMessage()));
            run.setFinishedAt(LocalDateTime.now());
            evalRunMapper.updateById(run);
            log.error("evaluation run failed, errorCode={}, runId={}", ErrorCode.INTERNAL_ERROR, runId, e);
        }
    }

    /**
     * Judges every case of one run on the shared, container managed case pool.
     *
     * <p>A pool created here per run would be invisible to the deployment - unnamed threads in a dump, no
     * request id carried into them, and a concurrency ceiling that multiplied by however many runs happened
     * to be executing. Every case is submitted before any is collected, so they overlap: joining inside the
     * submission loop would judge them one at a time.
     *
     * @param kbId         knowledge base business id
     * @param cases        the run's effective cases
     * @param config       retrieval configuration under test
     * @param k            metrics {@code K}
     * @param judgeEnabled {@code true} runs the LLM-as-judge stage
     * @return one outcome per case, in the order the cases were given
     */
    private List<CaseOutcome> judgeAll(String kbId, List<EvalCase> cases, EvalRetrievalConfig config, int k,
                                       boolean judgeEnabled) {
        if (CollectionUtils.isEmpty(cases)) {
            return List.of();
        }
        List<CompletableFuture<CaseOutcome>> futures = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> judgeOneCase(kbId, evalCase, config, k, judgeEnabled), evalCaseExecutor));
        }
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        try {
            for (CompletableFuture<CaseOutcome> future : futures) {
                outcomes.add(future.join());
            }
        } catch (CompletionException e) {
            throw new IllegalStateException("evaluation case execution failed", e.getCause());
        }
        return outcomes;
    }

    private CaseOutcome judgeOneCase(String kbId, EvalCase evalCase, EvalRetrievalConfig config, int k,
                                     boolean judgeEnabled) {
        RetrievalCommand command = toCommand(evalCase, config);
        int budget = properties.getEval().getDegradedRetry();
        SearchOutcome outcome = OfflineExecutionContext.runOffline(() -> retrievalService.search(kbId, command));
        int retries = 0;
        while (!outcome.getDegraded().isEmpty() && retries < budget) {
            retries++;
            outcome = OfflineExecutionContext.runOffline(() -> retrievalService.search(kbId, command));
        }

        List<RetrievalNodeView> nodes = outcome.getNodes();
        List<List<String>> candidateTextsPerRank = new ArrayList<>(nodes.size());
        List<String> docIdPerRank = new ArrayList<>(nodes.size());
        List<String> recalledChunkIds = new ArrayList<>(nodes.size());
        for (RetrievalNodeView node : nodes) {
            candidateTextsPerRank.add(childTextsOf(node));
            docIdPerRank.add(node.getDocId());
            recalledChunkIds.add(node.getChunkId());
        }

        List<EvalEvidence> evidences = evidencesOf(evalCase);
        CaseJudgment judgment;
        List<Double> overlapRatios = new ArrayList<>();
        if (evalCase.getAnchorType() == AnchorType.SPAN) {
            List<String> spans = evidences.stream().map(EvalEvidence::getSpan).toList();
            judgment = evalHitJudge.judgeSpanCase(spans, candidateTextsPerRank,
                    properties.getEval().getOverlapThreshold());
            for (String span : spans) {
                overlapRatios.add(bestOverlapRatio(candidateTextsPerRank, span));
            }
        } else {
            List<String> docIds = evidences.stream().map(EvalEvidence::getDocId).toList();
            judgment = evalHitJudge.judgeDocumentCase(docIds, docIdPerRank);
        }

        EvalJudgeService.JudgeOutcome judgeOutcome = null;
        if (judgeEnabled && evalCase.getExpectedAnswer() != null && !evalCase.getExpectedAnswer().isBlank()
                && evalJudgeService.isAvailable()) {
            List<String> allTexts = candidateTextsPerRank.stream().flatMap(List::stream).toList();
            judgeOutcome = evalJudgeService.judge(evalCase.getExpectedAnswer(), allTexts);
        }

        boolean multiTurn = evalCase.getMessages() != null && !evalCase.getMessages().isBlank();
        return new CaseOutcome(evalCase.getCaseId(), judgment, overlapRatios, recalledChunkIds,
                outcome.getDegraded(), retries, judgeOutcome, evalCase.getAnchorType(), multiTurn);
    }

    private double bestOverlapRatio(List<List<String>> candidateTextsPerRank, String span) {
        double best = 0.0d;
        for (List<String> rankTexts : candidateTextsPerRank) {
            for (String text : rankTexts) {
                best = Math.max(best, overlapRatioCalculator.overlapRatio(text, span));
            }
        }
        return best;
    }

    private List<String> childTextsOf(RetrievalNodeView node) {
        Object childrenRaw = node.getMetadata() == null ? null
                : node.getMetadata().get(RetrievalMetadataKeys.CHILDREN);
        if (!(childrenRaw instanceof List<?> children) || children.isEmpty()) {
            return List.of(node.getContent());
        }
        List<String> texts = new ArrayList<>(children.size());
        for (Object child : children) {
            if (child instanceof Map<?, ?> map) {
                Object content = map.get(RetrievalMetadataKeys.CHILD_CONTENT);
                if (content != null) {
                    texts.add(String.valueOf(content));
                }
            }
        }
        return texts.isEmpty() ? List.of(node.getContent()) : texts;
    }

    private RetrievalCommand toCommand(EvalCase evalCase, EvalRetrievalConfig config) {
        EvalMode mode = config.getMode();
        return RetrievalCommand.builder()
                .query(evalCase.getQuery())
                .messages(messagesOf(evalCase))
                .recallTopK(config.getRecallTopK())
                .topN(config.getTopN())
                .fusionMode(config.getFusion())
                .scoreThreshold(config.getScoreThreshold())
                .rewriteEnabled(config.getRewriteEnabled())
                .rerankEnabled(mode.rerankRequested())
                .bm25RouteEnabled(mode.bm25RouteEnabled())
                .vectorRouteEnabled(mode.vectorRouteEnabled())
                .build();
    }

    private List<ChatMessage> messagesOf(EvalCase evalCase) {
        if (evalCase.getMessages() == null || evalCase.getMessages().isBlank()) {
            return List.of();
        }
        List<ChatMessage> messages = JsonUtil.parse(evalCase.getMessages(), new TypeReference<List<ChatMessage>>() {
        });
        return messages == null ? List.of() : messages;
    }

    private List<EvalEvidence> evidencesOf(EvalCase evalCase) {
        List<EvalEvidence> evidences = JsonUtil.parse(evalCase.getEvidences(),
                new TypeReference<List<EvalEvidence>>() {
                });
        return evidences == null ? List.of() : evidences;
    }

    private EvalResult toEntity(String runId, CaseOutcome outcome) {
        EvalResult result = new EvalResult();
        result.setResultId(bizIdGenerator.evalResultId());
        result.setRunId(runId);
        result.setCaseId(outcome.caseId());
        result.setHit(outcome.judgment().hit() ? 1 : 0);
        result.setHitRank(outcome.judgment().hitRank());
        // Persisted since M4c: the release gate recomputes Recall@K on the intersection of two runs and
        // needs the counts the judgment already produced, see GateMetricsRecomputer.
        result.setEvidenceHitCount(outcome.judgment().evidenceHitCount());
        result.setEvidenceTotalCount(outcome.judgment().evidenceTotalCount());
        result.setOverlapRatios(JsonUtil.toJson(outcome.overlapRatios()));
        result.setRecalledChunkIds(JsonUtil.toJson(outcome.recalledChunkIds()));
        result.setDegraded(JsonUtil.toJson(outcome.degraded()));
        result.setRetryCount(outcome.retryCount());
        if (outcome.judgeOutcome() != null) {
            result.setJudgeScore(outcome.judgeOutcome().score());
            result.setJudgeReason(outcome.judgeOutcome().reason());
        }
        return result;
    }

    /**
     * Aggregates the metrics of one run's cases into the five group report the console renders,
     * requirement section 4.6 "report groups: overall/span/document/single turn/multi turn".
     *
     * @param outcomes  per case judgments actually produced
     * @param allCases  every non deprecated case, used only to know which ones were skipped stale
     * @param k         metrics {@code K}
     * @return metrics document, group omitted entirely when it has no case
     */
    private Map<String, Map<String, EvalMetricsAtK>> buildMetrics(List<CaseOutcome> outcomes,
                                                                   List<EvalCase> allCases, int k) {
        Map<String, Map<String, EvalMetricsAtK>> metrics = new LinkedHashMap<>();
        putGroup(metrics, EvalMetricGroups.OVERALL, outcomes, k);
        putGroup(metrics, EvalMetricGroups.SPAN,
                outcomes.stream().filter(o -> o.anchorType() == AnchorType.SPAN).toList(), k);
        putGroup(metrics, EvalMetricGroups.DOCUMENT,
                outcomes.stream().filter(o -> o.anchorType() == AnchorType.DOCUMENT).toList(), k);
        putGroup(metrics, EvalMetricGroups.SINGLE_TURN,
                outcomes.stream().filter(o -> !o.multiTurn()).toList(), k);
        putGroup(metrics, EvalMetricGroups.MULTI_TURN,
                outcomes.stream().filter(CaseOutcome::multiTurn).toList(), k);
        return metrics;
    }

    private void putGroup(Map<String, Map<String, EvalMetricsAtK>> metrics, String groupKey,
                          List<CaseOutcome> outcomes, int k) {
        if (outcomes.isEmpty()) {
            return;
        }
        List<CaseJudgment> judgments = outcomes.stream().map(CaseOutcome::judgment).toList();
        Map<String, EvalMetricsAtK> byK = new LinkedHashMap<>();
        byK.put(String.valueOf(k), evalMetricsCalculator.aggregate(judgments, k));
        metrics.put(groupKey, byK);
    }

    private String truncate(String message) {
        if (message == null) {
            return "internal error";
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }

    /**
     * Judgment of one case together with everything the drill down and the metrics grouping need.
     *
     * @param caseId         case business id
     * @param judgment       case level judgment
     * @param overlapRatios  best individual overlap ratio per evidence, empty for a document anchor
     * @param recalledChunkIds chunk ids the top K returned
     * @param degraded       degradation markers observed on the final attempt
     * @param retryCount     automatic retries actually performed
     * @param judgeOutcome   LLM-as-judge outcome, {@code null} when not judged
     * @param anchorType     anchoring granularity, for metrics grouping
     * @param multiTurn      {@code true} when the case carries a conversation history
     */
    private record CaseOutcome(String caseId, CaseJudgment judgment, List<Double> overlapRatios,
                               List<String> recalledChunkIds, List<String> degraded, int retryCount,
                               EvalJudgeService.JudgeOutcome judgeOutcome, AnchorType anchorType,
                               boolean multiTurn) {
    }

    /**
     * Predicted model calls of a submission, requirement section 4.6 "cost guard rail".
     *
     * @param embeddingCalls predicted embedding calls
     * @param rerankCalls    predicted rerank calls
     * @param rewriteCalls   predicted query rewrite calls
     * @param judgeCalls     predicted LLM-as-judge calls
     */
    public record EstimateResult(long embeddingCalls, long rerankCalls, long rewriteCalls, long judgeCalls) {
    }

    /**
     * Outcome of a compare request.
     *
     * @param comparable {@code true} when every run shares both {@code dataset_revision} and
     *                   {@code corpus_fingerprint}
     * @param reason     explanation, {@code null} when comparable
     * @param runs       the runs that were compared
     */
    public record CompareResult(boolean comparable, String reason, List<EvalRun> runs) {

        private static CompareResult comparable(List<EvalRun> runs) {
            return new CompareResult(true, null, runs);
        }

        private static CompareResult notComparable(List<EvalRun> runs, String reason) {
            return new CompareResult(false, reason, runs);
        }
    }
}
