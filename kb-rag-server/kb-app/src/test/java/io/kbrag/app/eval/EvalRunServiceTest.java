package io.kbrag.app.eval;

import io.kbrag.app.retrieval.OfflineExecutionContext;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
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
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.EvalHitJudge;
import io.kbrag.domain.service.EvalMetricsCalculator;
import io.kbrag.domain.service.OverlapRatioCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers evaluation run orchestration of requirement section 4.6: the zero key fail-fast for a vector
 * dependent mode, the degraded case automatic retry, and the compare endpoint's comparability rule.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalRunServiceTest {

    private static final String DATASET_ID = "evds_test";
    private static final String KB_ID = "kb_test";
    private static final String RUN_PROBE_THREAD = "eval-run-probe";
    private static final String CASE_PROBE_THREAD = "eval-case-probe";
    private static final int HAND_OFF_TIMEOUT_SECONDS = 5;

    private EvalDatasetService evalDatasetService;
    private EvalRunMapper evalRunMapper;
    private EvalResultMapper evalResultMapper;
    private EvalCaseMapper evalCaseMapper;
    private RetrievalService retrievalService;
    private EmbeddingProvider embeddingProvider;
    private RerankProvider rerankProvider;
    private ChatProvider chatProvider;
    private EvalJudgeService evalJudgeService;
    private CorpusFingerprintFactory corpusFingerprintFactory;
    private BizIdGenerator bizIdGenerator;
    private KbProperties properties;
    private EvalRunService service;

    @BeforeEach
    void setUp() {
        evalDatasetService = mock(EvalDatasetService.class);
        evalRunMapper = mock(EvalRunMapper.class);
        evalResultMapper = mock(EvalResultMapper.class);
        evalCaseMapper = mock(EvalCaseMapper.class);
        retrievalService = mock(RetrievalService.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        rerankProvider = mock(RerankProvider.class);
        chatProvider = mock(ChatProvider.class);
        evalJudgeService = mock(EvalJudgeService.class);
        corpusFingerprintFactory = mock(CorpusFingerprintFactory.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        properties = new KbProperties();

        service = newService(Runnable::run, Runnable::run);
    }

    /**
     * Builds the service over the two executors a case is routed through.
     *
     * <p>The suite runs both inline so its assertions about what a run produces stay free of thread
     * coordination; the two hand-off tests are where the executors are given real threads and the fact
     * that the work actually leaves the caller is what is being measured.
     *
     * @param evalExecutor     pool one run's execution is submitted to
     * @param evalCaseExecutor pool the cases of a run are spread over
     * @return service under test
     */
    private EvalRunService newService(Executor evalExecutor, Executor evalCaseExecutor) {
        return new EvalRunService(evalDatasetService, evalRunMapper, evalResultMapper, evalCaseMapper,
                retrievalService, embeddingProvider, rerankProvider, chatProvider, evalJudgeService,
                corpusFingerprintFactory, new EvalHitJudge(new OverlapRatioCalculator(new ChunkTextHasher())),
                new EvalMetricsCalculator(), new OverlapRatioCalculator(new ChunkTextHasher()),
                bizIdGenerator, properties, evalExecutor, evalCaseExecutor);
    }

    @Test
    void shouldFailFastWhenAVectorDependentModeHasNoEmbeddingProvider() {
        when(evalDatasetService.require(DATASET_ID)).thenReturn(dataset());
        when(corpusFingerprintFactory.fingerprint(KB_ID)).thenReturn("fp_1");
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(bizIdGenerator.evalRunId()).thenReturn("evr_1");

        List<EvalRun> runs = service.submit(DATASET_ID, 5, List.of(configOf(EvalMode.VECTOR_ONLY)), false);

        assertEquals(1, runs.size());
        assertEquals(RunStatus.FAILED, runs.get(0).getStatus());
        assertTrue(runs.get(0).getFailReason().contains("嵌入模型未配置"));
        verify(retrievalService, never()).search(anyString(), any());
    }

    @Test
    void shouldNotFailFastForBm25OnlyRegardlessOfEmbeddingAvailability() {
        when(evalDatasetService.require(DATASET_ID)).thenReturn(dataset());
        when(corpusFingerprintFactory.fingerprint(KB_ID)).thenReturn("fp_1");
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(bizIdGenerator.evalRunId()).thenReturn("evr_2");
        when(evalCaseMapper.selectList(any())).thenReturn(List.of());
        // execute() re-reads the run it was just handed the id of; a real database would return the row
        // createOne() inserted moments earlier, topN already resolved from k, so the mock is told to do
        // the same.
        EvalRun stored = runWithState("evr_2", 1, "fp_1");
        stored.setStatus(RunStatus.PENDING);
        EvalRetrievalConfig storedConfig = configOf(EvalMode.BM25_ONLY);
        storedConfig.setTopN(5);
        stored.setRetrievalConfig(JsonUtil.toJson(storedConfig));
        when(evalRunMapper.selectOne(any())).thenReturn(stored);

        List<EvalRun> runs = service.submit(DATASET_ID, 5, List.of(configOf(EvalMode.BM25_ONLY)), false);

        // createOne() returns the run object it built locally before insertion, which starts PENDING;
        // this suite's executors run inline, so execute() had already finished against the separately
        // mocked, re-fetched row by the time submit() returned, and that row is where the final state
        // actually landed.
        assertEquals(RunStatus.PENDING, runs.get(0).getStatus());
        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(evalRunMapper, org.mockito.Mockito.atLeastOnce()).updateById(runCaptor.capture());
        EvalRun finalState = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertEquals(RunStatus.SUCCESS, finalState.getStatus());
    }

    /**
     * A submission must hand the execution over and leave the request thread with nothing but the rows it
     * created.
     *
     * <p>Regression guard for the {@code @Async} self invocation this replaced. The annotation sat on a
     * method {@code createOne} called on its own bean, which the proxy never intercepts, so a submitted
     * matrix - up to six runs, every case of each - executed on the submitting HTTP thread while the pool
     * it named stood idle. Nothing about the produced rows differs between the two, which is exactly why
     * the assertion has to be about the thread.
     */
    @Test
    void shouldExecuteTheRunOnTheEvalExecutorRatherThanTheSubmittingThread() throws InterruptedException {
        when(evalDatasetService.require(DATASET_ID)).thenReturn(dataset());
        when(corpusFingerprintFactory.fingerprint(KB_ID)).thenReturn("fp_1");
        when(embeddingProvider.isConfigured()).thenReturn(false);
        when(bizIdGenerator.evalRunId()).thenReturn("evr_async");
        when(evalCaseMapper.selectList(any())).thenReturn(List.of());

        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<String> executionThread = new AtomicReference<>();
        when(evalRunMapper.selectOne(any())).thenAnswer(invocation -> {
            // requireRun is the first thing execute() does, so this is the thread the run really ran on.
            executionThread.set(Thread.currentThread().getName());
            executed.countDown();
            return storedRun("evr_async");
        });

        ExecutorService evalPool = Executors.newSingleThreadExecutor(task -> new Thread(task, RUN_PROBE_THREAD));
        try {
            EvalRunService asyncService = newService(evalPool, Runnable::run);

            asyncService.submit(DATASET_ID, 5, List.of(configOf(EvalMode.BM25_ONLY)), false);

            assertTrue(executed.await(HAND_OFF_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the run never reached the eval executor");
            assertEquals(RUN_PROBE_THREAD, executionThread.get());
            assertNotEquals(Thread.currentThread().getName(), executionThread.get());
        } finally {
            evalPool.shutdownNow();
        }
    }

    /**
     * The cases must be judged on the container managed pool, not on one the run creates for itself.
     *
     * <p>Regression guard for the per run {@code Executors.newFixedThreadPool} this replaced: its threads
     * were unnamed, carried no request id into the retrieval calls, and multiplied the configured
     * concurrency by however many runs happened to be executing.
     */
    @Test
    void shouldJudgeTheCasesOnTheCaseExecutorRatherThanAPoolCreatedPerRun() {
        EvalRun run = pendingRun();
        when(evalRunMapper.selectOne(any())).thenReturn(run);
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(spanCase()));
        when(bizIdGenerator.evalResultId()).thenReturn("evre_1");

        AtomicReference<String> caseThread = new AtomicReference<>();
        when(retrievalService.search(anyString(), any(RetrievalCommand.class))).thenAnswer(invocation -> {
            caseThread.set(Thread.currentThread().getName());
            return new SearchOutcome(List.of(hitNode()), List.of(), null);
        });

        ExecutorService casePool = Executors.newSingleThreadExecutor(task -> new Thread(task, CASE_PROBE_THREAD));
        try {
            // The run executor is inline here, so the case pool is the only hand-off left to observe and
            // join() has already made it deterministic by the time execute() returns.
            EvalRunService asyncService = newService(Runnable::run, casePool);

            asyncService.execute("evr_run", false);

            assertEquals(CASE_PROBE_THREAD, caseThread.get());
        } finally {
            casePool.shutdownNow();
        }
    }

    @Test
    void shouldRetryADegradedCaseUpToTheConfiguredBudgetAndKeepTheFinalOutcome() {
        properties.getEval().setDegradedRetry(2);
        EvalRun run = pendingRun();
        when(evalRunMapper.selectOne(any())).thenReturn(run);
        EvalCase evalCase = spanCase();
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(evalCase));
        when(bizIdGenerator.evalResultId()).thenReturn("evre_1");

        SearchOutcome degraded = new SearchOutcome(List.of(hitNode()), List.of("rerank_timeout"), null);
        SearchOutcome clean = new SearchOutcome(List.of(hitNode()), List.of(), null);
        when(retrievalService.search(anyString(), any(RetrievalCommand.class)))
                .thenReturn(degraded, degraded, clean);

        service.execute("evr_run", false);

        ArgumentCaptor<EvalResult> resultCaptor = ArgumentCaptor.forClass(EvalResult.class);
        verify(evalResultMapper).insert(resultCaptor.capture());
        assertEquals(2, resultCaptor.getValue().getRetryCount());
        assertEquals("[]", resultCaptor.getValue().getDegraded());

        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(evalRunMapper, org.mockito.Mockito.atLeastOnce()).updateById(runCaptor.capture());
        EvalRun finalState = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertEquals(RunStatus.SUCCESS, finalState.getStatus());
        assertEquals(0, finalState.getCaseDegraded());
    }

    @Test
    void shouldStopRetryingOnceTheBudgetIsExhaustedAndStillSucceedWithDegradedCasesRecorded() {
        properties.getEval().setDegradedRetry(1);
        EvalRun run = pendingRun();
        when(evalRunMapper.selectOne(any())).thenReturn(run);
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(spanCase()));
        when(bizIdGenerator.evalResultId()).thenReturn("evre_1");

        SearchOutcome degraded = new SearchOutcome(List.of(hitNode()), List.of("rerank_timeout"), null);
        when(retrievalService.search(anyString(), any(RetrievalCommand.class))).thenReturn(degraded);

        service.execute("evr_run", false);

        ArgumentCaptor<EvalResult> resultCaptor = ArgumentCaptor.forClass(EvalResult.class);
        verify(evalResultMapper).insert(resultCaptor.capture());
        assertEquals(1, resultCaptor.getValue().getRetryCount());

        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(evalRunMapper, org.mockito.Mockito.atLeastOnce()).updateById(runCaptor.capture());
        EvalRun finalState = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        // Still SUCCESS: M4b never gates on degradation, it only has to be counted and surfaced.
        assertEquals(RunStatus.SUCCESS, finalState.getStatus());
        assertEquals(1, finalState.getCaseDegraded());
    }

    @Test
    void shouldCompareTwoRunsOfTheSameRevisionAndCorpus() {
        EvalRun a = runWithState("evr_a", 3, "fp_1");
        EvalRun b = runWithState("evr_b", 3, "fp_1");
        when(evalRunMapper.selectOne(any())).thenReturn(a).thenReturn(b);

        EvalRunService.CompareResult result = service.compare(List.of("evr_a", "evr_b"));

        assertTrue(result.comparable());
    }

    @Test
    void shouldRefuseToCompareRunsOfDifferentDatasetRevisions() {
        EvalRun a = runWithState("evr_a", 3, "fp_1");
        EvalRun b = runWithState("evr_b", 4, "fp_1");
        when(evalRunMapper.selectOne(any())).thenReturn(a).thenReturn(b);

        EvalRunService.CompareResult result = service.compare(List.of("evr_a", "evr_b"));

        assertFalse(result.comparable());
        assertTrue(result.reason() != null && !result.reason().isBlank());
    }

    @Test
    void shouldRefuseToCompareRunsOfDifferentCorpusStates() {
        EvalRun a = runWithState("evr_a", 3, "fp_1");
        EvalRun b = runWithState("evr_b", 3, "fp_2");
        when(evalRunMapper.selectOne(any())).thenReturn(a).thenReturn(b);

        EvalRunService.CompareResult result = service.compare(List.of("evr_a", "evr_b"));

        assertFalse(result.comparable());
    }

    private EvalDataset dataset() {
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId(DATASET_ID);
        dataset.setKbId(KB_ID);
        dataset.setDatasetRevision(1);
        return dataset;
    }

    private EvalRetrievalConfig configOf(EvalMode mode) {
        EvalRetrievalConfig config = new EvalRetrievalConfig();
        config.setLabel("cfg");
        config.setMode(mode);
        return config;
    }

    private EvalRun pendingRun() {
        EvalRun run = new EvalRun();
        run.setRunId("evr_run");
        run.setDatasetId(DATASET_ID);
        run.setKbId(KB_ID);
        run.setDatasetRevision(1);
        run.setCorpusFingerprint("fp_1");
        run.setStatus(RunStatus.PENDING);
        EvalRetrievalConfig config = configOf(EvalMode.HYBRID);
        config.setTopN(3);
        run.setRetrievalConfig(JsonUtil.toJson(config));
        return run;
    }

    private EvalRun runWithState(String runId, int revision, String fingerprint) {
        EvalRun run = new EvalRun();
        run.setRunId(runId);
        run.setDatasetRevision(revision);
        run.setCorpusFingerprint(fingerprint);
        run.setRetrievalConfig(JsonUtil.toJson(configOf(EvalMode.HYBRID)));
        run.setStatus(RunStatus.SUCCESS);
        return run;
    }

    /**
     * The row {@code execute} re-reads for a run that was just submitted, as a real database would return
     * it: pending, with the top n already resolved from k.
     *
     * @param runId run business id
     * @return stored run
     */
    private EvalRun storedRun(String runId) {
        EvalRun stored = runWithState(runId, 1, "fp_1");
        stored.setStatus(RunStatus.PENDING);
        EvalRetrievalConfig config = configOf(EvalMode.BM25_ONLY);
        config.setTopN(5);
        stored.setRetrievalConfig(JsonUtil.toJson(config));
        return stored;
    }

    private EvalCase spanCase() {
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId("evc_1");
        evalCase.setDatasetId(DATASET_ID);
        evalCase.setQuery("why does it matter");
        evalCase.setAnchorType(AnchorType.SPAN);
        evalCase.setStatus(CaseStatus.ACTIVE);
        EvalEvidence evidence = new EvalEvidence();
        evidence.setDocId("doc_1");
        evidence.setSpan("the exact evidence text");
        evalCase.setEvidences(JsonUtil.toJson(List.of(evidence)));
        return evalCase;
    }

    private RetrievalNodeView hitNode() {
        return RetrievalNodeView.builder()
                .docId("doc_1")
                .documentVersionId("dv_1")
                .chunkId("ck_1")
                .chunkType("text")
                .content("prefix the exact evidence text suffix")
                .score(1.0d)
                .scoreType("cosine")
                .retrievalSource("vector")
                .metadata(java.util.Map.of())
                .imageUrls(List.of())
                .build();
    }
}
