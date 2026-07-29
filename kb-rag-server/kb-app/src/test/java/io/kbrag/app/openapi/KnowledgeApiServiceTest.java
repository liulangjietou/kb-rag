package io.kbrag.app.openapi;

import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.retrieval.AppliedInfo;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalIndexOverride;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.TargetStage;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.AppPromptConfig;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.ChatPromptAssembler;
import io.kbrag.domain.service.ContentBudgetTrimmer;
import io.kbrag.domain.service.RequestOverridePolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the open API orchestration of requirement sections 4.8 and 5: the version snapshot decides every
 * retrieval parameter, only four overrides are accepted, the content budget drops whole nodes, the zero key
 * deployment fails generation explicitly, and every call - accepted or rejected - lands in the audit trail.
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeApiServiceTest {

    private static final String KEY_ID = "ak_1";
    private static final String APP_ID = "app_1";
    private static final String KB_ID = "kb_1";
    private static final String VERSION_ID = "av_1";
    private static final String SNAPSHOT_BM25_INDEX = "kb_1_bm25_s1";
    private static final String SNAPSHOT_VECTOR_INDEX = "kb_1_tev4_s1";
    private static final String FROZEN_VERSION = "dv_frozen";

    private AppService appService;
    private AppVersionService appVersionService;
    private RetrievalService retrievalService;
    private ChatProviderFactory chatProviderFactory;
    private ChatProvider chatProvider;
    private ApiAuditService apiAuditService;
    private SimpleMeterRegistry meterRegistry;
    private KnowledgeApiService service;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        appVersionService = mock(AppVersionService.class);
        retrievalService = mock(RetrievalService.class);
        chatProviderFactory = mock(ChatProviderFactory.class);
        chatProvider = mock(ChatProvider.class);
        apiAuditService = mock(ApiAuditService.class);
        when(appService.require(APP_ID)).thenReturn(new App());
        when(chatProviderFactory.forModel(any())).thenReturn(chatProvider);
        meterRegistry = new SimpleMeterRegistry();
        service = new KnowledgeApiService(appService, appVersionService, retrievalService, chatProviderFactory,
                new ChatPromptAssembler(), new ContentBudgetTrimmer(), new RequestOverridePolicy(),
                apiAuditService, mock(SearchInsightService.class), new KbMetrics(meterRegistry));
    }

    @Test
    void shouldTakeEveryFrozenRetrievalParameterFromTheVersionSnapshot() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), command(null, null, null));

        ArgumentCaptor<RetrievalCommand> captor = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrievalService).search(eq(List.of(KbRef.of(KB_ID))), captor.capture());
        RetrievalCommand issued = captor.getValue();
        assertEquals(37, issued.getRecallTopK());
        assertEquals(4, issued.getTopN());
        assertEquals("weighted", issued.getFusionMode());
        assertEquals(Boolean.TRUE, issued.getRerankEnabled());
        assertEquals(Boolean.FALSE, issued.getRewriteEnabled());
        // The M13 search timer sits on the same insight point every open flow passes.
        assertEquals(1, meterRegistry.get("kb.search").tag("source", "open_api").timer().count());
    }

    @Test
    void shouldBindTheFrozenIndexSnapshotForAReleasedVersion() {
        stubVersionWithSnapshot(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), command(null, null, null));

        RetrievalCommand issued = capturedRetrieval();
        // Requirement section 4.7: an external call against the released version reads the corpus its gate
        // measured, addressed by physical name because a snapshot carries no alias.
        assertEquals(new RetrievalIndexOverride(SNAPSHOT_BM25_INDEX, SNAPSHOT_VECTOR_INDEX),
                issued.getIndexOverride().get(KB_ID));
        assertEquals(List.of(FROZEN_VERSION), issued.getVisibleVersionIdsOverride().get(KB_ID));
    }

    @Test
    void shouldNotBindASnapshotForABetaCallAgainstATestVersion() {
        AppVersion version = stubVersionWithSnapshot(AppVersionStatus.TESTING);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), command(null, null, null));

        // A beta call is a gradual rollout against the current corpus, so it reads the live alias even if the
        // row happened to carry frozen columns.
        assertNull(capturedRetrieval().getIndexOverride());
        assertNull(capturedRetrieval().getVisibleVersionIdsOverride());
        assertEquals(AppVersionStatus.TESTING, version.getStatus());
    }

    @Test
    void shouldNotBindASnapshotForALegacyReleaseThatFrozeNothing() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), command(null, null, null));

        // A version released before this milestone has nothing frozen; falling back to the live alias is its
        // designed behaviour rather than a fault.
        assertNull(capturedRetrieval().getIndexOverride());
        assertNull(capturedRetrieval().getVisibleVersionIdsOverride());
    }

    @Test
    void shouldNotBindASnapshotForAConsolePreviewOfTheReleasedVersion() {
        stubVersionWithSnapshot(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("answer");

        service.preview(APP_ID, VERSION_ID, command(null, null, null), null);

        // The preview is how an operator proves the snapshot isolates the release: the same query has to return
        // the newly indexed content here and not through the open API.
        assertNull(capturedRetrieval().getIndexOverride());
        assertNull(capturedRetrieval().getVisibleVersionIdsOverride());
    }

    @Test
    void shouldLetTheWhiteListedTopNOverrideTheSnapshotValue() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), command(2, null, null));

        ArgumentCaptor<RetrievalCommand> captor = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrievalService).search(eq(List.of(KbRef.of(KB_ID))), captor.capture());
        assertEquals(2, captor.getValue().getTopN());
        // The frozen recall stays frozen: the white list shapes the response, never the retrieval behaviour.
        assertEquals(37, captor.getValue().getRecallTopK());
    }

    @Test
    void shouldRejectAnOverrideOutsideTheWhiteListAndStillAuditTheCall() {
        stubVersion(AppVersionStatus.RELEASED);

        BizException e = assertThrows(BizException.class, () -> service.search(principal(List.of()),
                commandWithKeys(Set.of("recall_top_k"))));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        verify(retrievalService, never()).search(anyList(), any());
        assertEquals(ErrorCode.INVALID_PARAM.name(), capturedAudit().getErrorCode());
    }

    @Test
    void shouldDenyAnApplicationOutsideTheKeyScopeAndAuditTheRejection() {
        BizException e = assertThrows(BizException.class,
                () -> service.search(principal(List.of("app_other")), command(null, null, null)));

        assertEquals(ErrorCode.APP_ACCESS_DENIED, e.getErrorCode());
        // The scope check runs before the application lookup, so an unauthorised caller learns nothing about
        // which applications exist.
        verify(appService, never()).require(anyString());
        ApiAuditService.AuditRecord audited = capturedAudit();
        assertEquals(ErrorCode.APP_ACCESS_DENIED.name(), audited.getErrorCode());
        assertEquals(APP_ID, audited.getAppId());
        assertNull(audited.getAppVersionId());
    }

    @Test
    void shouldDropWholeNodesToFitTheContentBudget() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "a".repeat(100)), node("doc_2", "b".repeat(100)));

        KnowledgeCallResult result = service.search(principal(List.of()), command(null, null, 150));

        assertEquals(1, result.getNodes().size());
        assertEquals(100, result.getNodes().get(0).getContent().length());
    }

    @Test
    void shouldMarkACallThatNamedATestVersionAsBeta() {
        stubVersion(AppVersionStatus.TESTING);
        stubSearch(node("doc_1", "第一段"));

        KnowledgeCallResult result = service.search(principal(List.of()), command(null, "V2.0", null));

        assertEquals(TargetStage.BETA, result.getTargetStage());
        assertEquals(TargetStage.BETA, capturedAudit().getTargetStage());
    }

    @Test
    void shouldAuditTheHitDocumentsTheAppliedOverridesAndTheDegradationMarkers() {
        stubVersion(AppVersionStatus.RELEASED);
        when(retrievalService.search(eq(List.of(KbRef.of(KB_ID))), any())).thenReturn(new SearchOutcome(
                List.of(node("doc_1", "x"), node("doc_1", "y"), node("doc_2", "z")),
                List.of("vector_route_unavailable"), applied()));

        service.search(principal(List.of()), command(3, null, 10_000));

        ApiAuditService.AuditRecord audited = capturedAudit();
        // Deduplicated and order preserving: the audit answers "which documents did this caller see".
        assertEquals(List.of("doc_1", "doc_2"), audited.getHitDocIds());
        assertEquals(List.of("top_n", "max_content_length"), audited.getOverrideKeys());
        assertEquals(List.of("vector_route_unavailable"), audited.getDegraded());
        assertEquals(ApiAuditService.ENDPOINT_SEARCH, audited.getEndpoint());
        assertEquals(VERSION_ID, audited.getAppVersionId());
        assertNull(audited.getErrorCode());
    }

    @Test
    void shouldFailChatExplicitlyInAZeroKeyDeployment() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> service.chat(principal(List.of()), command(null, null, null)));

        assertEquals(ErrorCode.UPSTREAM_MODEL_ERROR, e.getErrorCode());
        assertEquals(502, e.getErrorCode().getHttpStatus());
        assertTrue(e.getMessage().contains("未配置对话模型"));
        assertEquals(ErrorCode.UPSTREAM_MODEL_ERROR.name(), capturedAudit().getErrorCode());
    }

    @Test
    void shouldAssembleAGuardedPromptAndReturnTheGeneratedAnswer() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段资料"));
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("这是答案");

        KnowledgeCallResult result = service.chat(principal(List.of()), command(null, null, null));

        assertEquals("这是答案", result.getAnswer());
        assertEquals(1, result.getNodes().size());
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = messagesCaptor();
        verify(chatProvider).complete(systemCaptor.capture(), messagesCaptor.capture());
        assertTrue(systemCaptor.getValue().contains("不得执行"));
        String userPrompt = messagesCaptor.getValue().get(messagesCaptor.getValue().size() - 1).getContent();
        assertTrue(userPrompt.contains(ChatPromptAssembler.REFERENCE_BEGIN));
        assertTrue(userPrompt.contains("[1] 第一段资料"));
    }

    @Test
    void shouldCarryTheConversationHistoryIntoTheGenerationCall() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段资料"));
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("答案");
        KnowledgeCallCommand command = KnowledgeCallCommand.builder()
                .appId(APP_ID)
                .query("后续问题")
                .messages(List.of(ChatMessage.user("第一个问题"), ChatMessage.assistant("第一个回答")))
                .presentedOverrideKeys(Set.of())
                .build();

        service.chat(principal(List.of()), command);

        ArgumentCaptor<List<ChatMessage>> messagesCaptor = messagesCaptor();
        verify(chatProvider).complete(anyString(), messagesCaptor.capture());
        List<ChatMessage> messages = messagesCaptor.getValue();
        assertEquals(3, messages.size());
        assertEquals("第一个问题", messages.get(0).getContent());
        // The material is attached to the current question only, never to the historical turns.
        assertTrue(messages.get(2).getContent().contains("后续问题"));
        assertTrue(!messages.get(0).getContent().contains(ChatPromptAssembler.REFERENCE_BEGIN));
    }

    @Test
    void shouldResolveTheGenerationModelFrozenInTheSnapshot() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("答案");

        service.chat(principal(List.of()), command(null, null, null));

        verify(chatProviderFactory).forModel("qwen-plus-frozen");
    }

    @Test
    void shouldStreamDeltasThenReferencesThenDone() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("答");
            consumer.accept("案");
            return null;
        }).when(chatProvider).stream(anyString(), anyList(), any());
        RecordingListener listener = new RecordingListener();

        service.chatStream(principal(List.of()), command(null, null, null), listener);

        assertEquals(List.of("delta:答", "delta:案", "references:1", "done"), listener.events);
    }

    @Test
    void shouldTerminateAStreamWithAnErrorEventCarryingTheBusinessCode() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(false);
        RecordingListener listener = new RecordingListener();

        service.chatStream(principal(List.of()), command(null, null, null), listener);

        assertEquals(1, listener.events.size());
        assertTrue(listener.events.get(0).startsWith("error:UPSTREAM_MODEL_ERROR"));
    }

    @Test
    void shouldRefuseToServeAVersionWithoutAKnowledgeBase() {
        AppVersion version = new AppVersion();
        version.setAppVersionId(VERSION_ID);
        version.setAppId(APP_ID);
        version.setVersion("V1.0");
        version.setStatus(AppVersionStatus.RELEASED);
        when(appVersionService.resolveForCall(eq(APP_ID), any())).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(new AppConfigSnapshot());

        BizException e = assertThrows(BizException.class,
                () -> service.search(principal(List.of()), command(null, null, null)));

        assertEquals(ErrorCode.VERSION_NOT_PUBLISHED, e.getErrorCode());
    }

    @Test
    void shouldNotAuditAConsolePreview() {
        AppVersion version = versionOf(AppVersionStatus.DRAFT);
        when(appVersionService.requireNewest(APP_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        stubSearch(node("doc_1", "第一段"));
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("预览答案");

        KnowledgeCallResult result = service.preview(APP_ID, null, command(null, null, null), null);

        assertEquals("预览答案", result.getAnswer());
        // Management traffic must not enter the external call volume statistics.
        verify(apiAuditService, never()).recordAsync(any());
    }

    @Test
    void shouldPassTheAttachedImagesUntouchedIntoTheRetrievalCommand() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        String image = image();
        service.search(principal(List.of()), commandWithImages(List.of(image)));

        // The open API no longer folds images itself: the single dispatch point lives in RetrievalService, so
        // the raw images and the untouched query are what the pipeline receives.
        RetrievalCommand issued = capturedRetrieval();
        assertEquals(List.of(image), issued.getImages());
        assertEquals("保险条款怎么算", issued.getQuery());
    }

    @Test
    void shouldDigestTheEffectiveQueryTheRankingCameFromIntoTheAuditRow() {
        stubVersion(AppVersionStatus.RELEASED);
        stubSearch(node("doc_1", "第一段"));

        service.search(principal(List.of()), commandWithImages(List.of(image())));

        // An operator reading the trail has to see the text the ranking actually came from; the pipeline
        // reports it back through the applied summary, which after an image dispatch or a rewrite may differ
        // from the written question.
        assertEquals("q", capturedAudit().getQuery());
    }

    @Test
    void shouldPassTheImageRouteDegradationThroughFromThePipeline() {
        stubVersion(AppVersionStatus.RELEASED);
        when(retrievalService.search(eq(List.of(KbRef.of(KB_ID))), any())).thenReturn(new SearchOutcome(
                new ArrayList<>(List.of(node("doc_1", "第一段"))),
                List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code(),
                        DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code()), applied()));

        KnowledgeCallResult result = service.search(principal(List.of()),
                commandWithImages(List.of(image())));

        // The image stage now runs inside RetrievalService, so its marker travels in the search outcome and
        // the open API forwards it flat, without a merge step of its own.
        assertEquals(List.of(DegradedReason.IMAGE_UNDERSTANDING_UNAVAILABLE.code(),
                DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code()), result.getDegraded());
    }

    @Test
    void shouldAuditARejectionTheRetrievalPipelineRaised() {
        stubVersion(AppVersionStatus.RELEASED);
        when(retrievalService.search(eq(List.of(KbRef.of(KB_ID))), any()))
                .thenThrow(new BizException(ErrorCode.INVALID_PARAM, "图片无法识别且未提供文字问题"));

        BizException failure = assertThrows(BizException.class,
                () -> service.search(principal(List.of()), commandWithImages(List.of(image()))));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        // A rejection the dispatch single point raised is audited like any other failed call.
        assertEquals(ErrorCode.INVALID_PARAM.name(), capturedAudit().getErrorCode());
    }

    private void stubVersion(AppVersionStatus status) {
        AppVersion version = versionOf(status);
        when(appVersionService.resolveForCall(eq(APP_ID), any())).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
    }

    private AppVersion stubVersionWithSnapshot(AppVersionStatus status) {
        AppVersion version = versionOf(status);
        version.setIndexSnapshots(JsonUtil.toJson(List.of(
                new AppIndexSnapshot(KB_ID, "es", SNAPSHOT_BM25_INDEX),
                new AppIndexSnapshot(KB_ID, "qdrant", SNAPSHOT_VECTOR_INDEX))));
        version.setVisibleVersionIds(JsonUtil.toJson(Map.of(KB_ID, List.of(FROZEN_VERSION))));
        when(appVersionService.resolveForCall(eq(APP_ID), any())).thenReturn(version);
        when(appVersionService.require(VERSION_ID)).thenReturn(version);
        when(appVersionService.requireNewest(APP_ID)).thenReturn(version);
        when(appVersionService.parseConfig(version)).thenReturn(snapshot());
        return version;
    }

    private AppVersion versionOf(AppVersionStatus status) {
        AppVersion version = new AppVersion();
        version.setAppVersionId(VERSION_ID);
        version.setAppId(APP_ID);
        version.setVersion("V2.0");
        version.setStatus(status);
        return version;
    }

    /**
     * The retrieval command the service issued.
     *
     * @return issued command
     */
    private RetrievalCommand capturedRetrieval() {
        ArgumentCaptor<RetrievalCommand> captor = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrievalService, org.mockito.Mockito.atLeastOnce())
                .search(eq(List.of(KbRef.of(KB_ID))), captor.capture());
        return captor.getValue();
    }

    private void stubSearch(RetrievalNodeView... nodes) {
        when(retrievalService.search(eq(List.of(KbRef.of(KB_ID))), any()))
                .thenReturn(new SearchOutcome(new ArrayList<>(List.of(nodes)), List.of(), applied()));
    }

    private AppliedInfo applied() {
        return AppliedInfo.builder().rewriteUsedQuery("q").fusionMode("rrf").thresholdAppliedOn("none")
                .routedKbIds(List.of(KB_ID)).build();
    }

    private AppConfigSnapshot snapshot() {
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(KbRef.of(KB_ID)));
        snapshot.setChatModel("qwen-plus-frozen");
        KbRetrievalConfig retrieval = new KbRetrievalConfig();
        retrieval.setRecallTopK(37);
        retrieval.setTopN(4);
        retrieval.setFusionMode("weighted");
        retrieval.setRerankEnabled(true);
        retrieval.setRewriteEnabled(false);
        snapshot.setRetrieval(retrieval);
        snapshot.setPrompt(AppPromptConfig.defaults());
        return snapshot;
    }

    private ApiKeyPrincipal principal(List<String> scope) {
        return new ApiKeyPrincipal(KEY_ID, "agent", 10, scope);
    }

    private KnowledgeCallCommand command(Integer topN, String version, Integer maxContentLength) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (topN != null) {
            keys.add("top_n");
        }
        if (maxContentLength != null) {
            keys.add("max_content_length");
        }
        return KnowledgeCallCommand.builder()
                .appId(APP_ID)
                .appVersion(version)
                .query("保险条款怎么算")
                .messages(List.of())
                .topN(topN)
                .maxContentLength(maxContentLength)
                .presentedOverrideKeys(keys)
                .build();
    }

    private KnowledgeCallCommand commandWithImages(List<String> images) {
        return KnowledgeCallCommand.builder()
                .appId(APP_ID)
                .query("保险条款怎么算")
                .messages(List.of())
                .images(images)
                .presentedOverrideKeys(Set.of())
                .build();
    }

    /**
     * Base64 of a small arbitrary payload; the bytes never reach a real provider in these tests.
     *
     * @return base64 image payload
     */
    private String image() {
        return java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
    }

    private KnowledgeCallCommand commandWithKeys(Set<String> keys) {
        return KnowledgeCallCommand.builder()
                .appId(APP_ID)
                .query("保险条款怎么算")
                .messages(List.of())
                .presentedOverrideKeys(keys)
                .build();
    }

    private RetrievalNodeView node(String docId, String content) {
        return RetrievalNodeView.builder()
                .docId(docId)
                .documentVersionId("dv_1")
                .chunkId("ck_" + content.hashCode())
                .chunkType("text")
                .content(content)
                .score(0.9d)
                .scoreType("cosine")
                .retrievalSource("bm25")
                .metadata(Map.of())
                .imageUrls(List.of())
                .build();
    }

    private ApiAuditService.AuditRecord capturedAudit() {
        ArgumentCaptor<ApiAuditService.AuditRecord> captor =
                ArgumentCaptor.forClass(ApiAuditService.AuditRecord.class);
        verify(apiAuditService).recordAsync(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ChatMessage>> messagesCaptor() {
        return ArgumentCaptor.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
    }

    /**
     * Records the event sequence of a stream so the order can be asserted exactly.
     */
    private static final class RecordingListener implements ChatStreamListener {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onDelta(String delta) {
            events.add("delta:" + delta);
        }

        @Override
        public void onReferences(List<RetrievalNodeView> references) {
            events.add("references:" + references.size());
        }

        @Override
        public void onDone(String requestId, List<String> degraded, List<String> routedKbIds) {
            events.add("done");
        }

        @Override
        public void onError(String code, String message) {
            events.add("error:" + code);
        }
    }
}
