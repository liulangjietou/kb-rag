package io.kbrag.app.workspace;

import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.chat.AnswerGenerationService;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.openapi.ApiAuditService;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.app.openapi.KnowledgeCallCommand;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalIndexContextResolver;
import io.kbrag.app.retrieval.RetrievalIndexOverride;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.ContentBudgetTrimmer;
import io.kbrag.domain.service.RequestOverridePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 员工正式运行的读取语义和生成前持久化边界。 */
class EmployeeStrictSnapshotTest {
    private final IndexAliasManager aliases = mock(IndexAliasManager.class);
    private final ActiveVersionResolver activeVersions = mock(ActiveVersionResolver.class);
    private final DocumentAclService acl = mock(DocumentAclService.class);
    private final FulltextStore fulltext = mock(FulltextStore.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final RetrievalIndexContextResolver resolver = new RetrievalIndexContextResolver(aliases, activeVersions, acl, fulltext, vectors);
    private final AppVersionService appVersions = mock(AppVersionService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final AnswerGenerationService generation = mock(AnswerGenerationService.class);
    private final KnowledgeApiService knowledge = new KnowledgeApiService(mock(AppService.class), appVersions, retrieval,
            generation, new ContentBudgetTrimmer(), new RequestOverridePolicy(), mock(ApiAuditService.class),
            mock(SearchInsightService.class), mock(KbMetrics.class));

    @BeforeEach
    void setUp() {
        when(fulltext.indexExists("frozen_index")).thenReturn(true);
        when(acl.trimRestricted(anyString(), anyList())).thenAnswer(call -> call.getArgument(1));
        when(appVersions.parseConfig(any())).thenAnswer(call -> {
            AppVersion version = call.getArgument(0);
            return JsonUtil.parse(version.getConfig(), AppConfigSnapshot.class);
        });
        when(retrieval.search(anyList(), any())).thenReturn(new SearchOutcome(List.of(), List.of(), null));
    }

    @Test
    void shouldKeepEmptyFrozenSetEmptyWithoutReadingLiveAliases() {
        var command = RetrievalCommand.builder().strictSnapshot(true)
                .indexOverride(Map.of("kb", new RetrievalIndexOverride("frozen_index", null)))
                .visibleVersionIdsOverride(Map.of("kb", List.of())).build();
        var resolved = resolver.resolve("kb", command);
        assertTrue(resolved.snapshotBound());
        assertTrue(resolved.visibleVersionIds().isEmpty());
        verifyNoInteractions(aliases, activeVersions);
    }

    @Test
    void shouldRejectMissingIndexOrVisibilityBindingInsteadOfFallback() {
        var missingVisibility = RetrievalCommand.builder().strictSnapshot(true)
                .indexOverride(Map.of("kb", new RetrievalIndexOverride("frozen_index", null))).build();
        assertEquals(ErrorCode.KNOWLEDGE_SNAPSHOT_UNAVAILABLE,
                assertThrows(BizException.class, () -> resolver.resolve("kb", missingVisibility)).getErrorCode());
        var missingIndex = RetrievalCommand.builder().strictSnapshot(true)
                .indexOverride(Map.of("kb", new RetrievalIndexOverride("gone", null)))
                .visibleVersionIdsOverride(Map.of("kb", List.of("version_old"))).build();
        assertEquals(ErrorCode.KNOWLEDGE_SNAPSHOT_UNAVAILABLE,
                assertThrows(BizException.class, () -> resolver.resolve("kb", missingIndex)).getErrorCode());
        verifyNoInteractions(aliases, activeVersions);
    }

    @Test
    void shouldUseCapturedVersionAndPersistEvidenceBeforeGeneration() {
        List<String> order = new ArrayList<>();
        doAnswer(call -> {
            order.add("generate");
            call.<Consumer<String>>getArgument(4).accept("答案");
            return null;
        }).when(generation).stream(any(), anyString(), anyList(), anyList(), any(), any());
        var result = knowledge.employeeStream(target(true), command(),
                retrieved -> order.add("persist evidence"), delta -> order.add(delta), new ChatCancellation());
        assertEquals(List.of("persist evidence", "generate", "答案"), order);
        assertEquals("av_old", result.getAppVersionId());
        ArgumentCaptor<RetrievalCommand> issued = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrieval).search(anyList(), issued.capture());
        assertTrue(issued.getValue().isStrictSnapshot());
        assertEquals("frozen_index", issued.getValue().getIndexOverride().get("kb").fulltextIndex());
        assertEquals(List.of("version_old"), issued.getValue().getVisibleVersionIdsOverride().get("kb"));
    }

    @Test
    void shouldNotGenerateWhenEvidencePersistenceFailsOrExplicitStopWins() {
        assertThrows(BizException.class, () -> knowledge.employeeStream(target(true), command(),
                retrieved -> { throw new BizException(ErrorCode.INTERNAL_ERROR, "save failed"); }, delta -> { }, new ChatCancellation()));
        ChatCancellation cancellation = new ChatCancellation();
        assertThrows(CancellationException.class, () -> knowledge.employeeStream(target(true), command(),
                retrieved -> cancellation.cancel(), delta -> { }, cancellation));
        verifyNoInteractions(generation);
    }

    @Test
    void shouldKeepLegacyLiveSemanticsExplicit() {
        knowledge.employeeStream(target(false), command(), retrieved -> { }, delta -> { }, new ChatCancellation());
        ArgumentCaptor<RetrievalCommand> issued = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrieval).search(anyList(), issued.capture());
        assertFalse(issued.getValue().isStrictSnapshot());
        assertEquals(null, issued.getValue().getIndexOverride());
    }

    private KnowledgeCallCommand command() {
        return KnowledgeCallCommand.builder().appId("app").query("问题").messages(List.of()).build();
    }

    private EmployeeRunTarget target(boolean frozen) {
        var config = new AppConfigSnapshot();
        config.setKbRefs(List.of(KbRef.of("kb")));
        return new EmployeeRunTarget("app", "av_old", "V1.0", JsonUtil.toJson(config),
                "[{\"kb_id\":\"kb\",\"engine\":\"es\",\"physical_index_name\":\"frozen_index\"}]",
                "{\"kb\":[\"version_old\"]}", frozen);
    }
}
