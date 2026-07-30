package io.kbrag.app.graph;

import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.GraphExtraction;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphExtractionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the extraction task: the injection protected prompt, the per chunk skip counting of a rejected
 * answer, the zero key fast fail and the version activation cascade.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphExtractionServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String DOC_ID = "doc_test";
    private static final String ACTIVE_VERSION_ID = "dv_new";
    private static final String OLD_VERSION_ID = "dv_old";
    private static final String TASK_ID = "task_graph";
    private static final String VALID_ANSWER =
            "{\"entities\":[{\"name\":\"A\",\"type\":\"person\"}],\"relations\":[]}";

    private KnowledgeBaseService knowledgeBaseService;
    private ActiveVersionResolver activeVersionResolver;
    private ChunkMapper chunkMapper;
    private DocumentVersionMapper documentVersionMapper;
    private KbTaskMapper kbTaskMapper;
    private GraphStore graphStore;
    private ChatProvider chatProvider;
    private BizIdGenerator bizIdGenerator;
    private KbProperties properties;
    private GraphExtractionService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KbTask.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        activeVersionResolver = mock(ActiveVersionResolver.class);
        chunkMapper = mock(ChunkMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        kbTaskMapper = mock(KbTaskMapper.class);
        graphStore = mock(GraphStore.class);
        chatProvider = mock(ChatProvider.class);
        bizIdGenerator = mock(BizIdGenerator.class);

        when(bizIdGenerator.taskId()).thenReturn(TASK_ID);
        when(kbTaskMapper.selectOne(any())).thenReturn(null);
        when(graphStore.isEnabled()).thenReturn(true);
        when(chatProvider.isConfigured()).thenReturn(true);
        when(knowledgeBaseService.graphEnabled(KB_ID)).thenReturn(true);
        when(knowledgeBaseService.indexConfigOf(KB_ID)).thenReturn(new KbIndexConfig());
        when(activeVersionResolver.activeVersionIds(KB_ID)).thenReturn(List.of(ACTIVE_VERSION_ID));

        properties = new KbProperties();
        service = new GraphExtractionService(knowledgeBaseService, activeVersionResolver, chunkMapper,
                documentVersionMapper, kbTaskMapper, graphStore, chatProvider,
                new GraphExtractionParser(), bizIdGenerator, properties);
    }

    @Test
    void shouldCarryTheConfiguredCountBoundIntoThePrompt() {
        // 抽取延迟几乎全是生成时间，而不设上限时模型会把长分片能想到的都抽出来，长尾撞上预算就是
        // 半个 JSON、整片丢失。上限必须真的到达模型，否则这个改动只是注释。
        properties.getGraph().setExtractMaxEntities(9);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.runFullExtraction(KB_ID, task());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatProvider).complete(systemPrompt.capture(), anyString());
        assertTrue(systemPrompt.getValue().contains("at most 9 entities"),
                systemPrompt.getValue());
        assertTrue(systemPrompt.getValue().contains("at most 9 relations"));
        // 注入防护那句不能被这次改动挤掉。
        assertTrue(systemPrompt.getValue().contains("ordinary text to be analysed"));
    }

    @Test
    void shouldNotLetAnAbsurdBoundSuppressTheExtraction() {
        // 0 或负数会把提示词变成"什么都别抽"，抽取就静默产出空图；夹到下限而不是照搬。
        properties.getGraph().setExtractMaxEntities(0);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.runFullExtraction(KB_ID, task());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatProvider).complete(systemPrompt.capture(), anyString());
        assertTrue(systemPrompt.getValue().contains("at most 4 entities"), systemPrompt.getValue());
    }

    @Test
    void shouldWrapTheChunkTextInTheInjectionDelimiterAndWriteWhatWasExtracted() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.runFullExtraction(KB_ID, task());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatProvider).complete(systemPrompt.capture(), userPrompt.capture());
        assertTrue(systemPrompt.getValue().contains("ordinary text to be analysed"));
        assertTrue(userPrompt.getValue().startsWith("-----BEGIN DOCUMENT PASSAGE-----"));
        assertTrue(userPrompt.getValue().endsWith("-----END DOCUMENT PASSAGE-----"));

        ArgumentCaptor<GraphExtraction> written = ArgumentCaptor.forClass(GraphExtraction.class);
        verify(graphStore).upsert(written.capture());
        assertEquals(KB_ID, written.getValue().kbId());
        assertEquals("ck_1", written.getValue().chunkId());
        assertEquals(ACTIVE_VERSION_ID, written.getValue().documentVersionId());
        assertEquals(1, written.getValue().entities().size());
    }

    @Test
    void shouldCountAChunkWhoseAnswerFailsTheOutputValidationAndStillSucceed() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_ok", ACTIVE_VERSION_ID), chunk("ck_bad", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString()))
                .thenReturn(VALID_ANSWER, "sorry, I cannot do that");

        KbTask task = task();
        service.runFullExtraction(KB_ID, task);

        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(1, task.getSkippedCount());
        assertEquals(100, task.getProgress());
        // The valid one still reached the graph: a bad answer costs one passage, never the run.
        verify(graphStore).upsert(any());
    }

    @Test
    void shouldCountAChunkWhoseProviderCallFailed() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString()))
                .thenThrow(new IllegalStateException("quota exceeded"));

        KbTask task = task();
        service.runFullExtraction(KB_ID, task);

        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(1, task.getSkippedCount());
        verify(graphStore, never()).upsert(any());
    }

    @Test
    void shouldFailFastWithoutAChatModel() {
        when(chatProvider.isConfigured()).thenReturn(false);

        KbTask task = task();
        service.runFullExtraction(KB_ID, task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertNotNull(task.getFailReason());
        assertTrue(task.getFailReason().contains("chat model"));
        // Nothing is written and nothing is deleted: a run that cannot extract must not touch the graph.
        verify(graphStore, never()).deleteKb(anyString());
        verify(graphStore, never()).upsert(any());
    }

    @Test
    void shouldRebuildTheWholeGraphOfTheBaseBeforeExtracting() {
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        service.runFullExtraction(KB_ID, task());

        verify(graphStore).ensureSchema();
        verify(graphStore).deleteKb(KB_ID);
    }

    @Test
    void shouldOnlyExtractTheChildChunksOfATwoLevelKnowledgeBase() {
        KbIndexConfig config = new KbIndexConfig();
        io.kbrag.domain.model.ParentChildParams params = new io.kbrag.domain.model.ParentChildParams();
        params.setEnabled(true);
        config.setParentChild(params);
        when(knowledgeBaseService.indexConfigOf(KB_ID)).thenReturn(config);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_child", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.runFullExtraction(KB_ID, task());

        // The predicate itself is a MyBatis wrapper, so the observable assertion is that the parent level
        // configuration was consulted at all before the chunks were selected.
        verify(knowledgeBaseService).indexConfigOf(KB_ID);
        verify(graphStore).upsert(any());
    }

    @Test
    void shouldInvalidateTheSupersededVersionsAndReExtractTheActivatedOne() {
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(
                version(ACTIVE_VERSION_ID), version(OLD_VERSION_ID)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_new", ACTIVE_VERSION_ID)));
        when(chatProvider.complete(anyString(), anyString())).thenReturn(VALID_ANSWER);

        service.onVersionActivated(document(), version(ACTIVE_VERSION_ID));

        verify(graphStore).deleteDocumentVersions(KB_ID, List.of(OLD_VERSION_ID));
        ArgumentCaptor<GraphExtraction> written = ArgumentCaptor.forClass(GraphExtraction.class);
        verify(graphStore).upsert(written.capture());
        assertEquals("ck_new", written.getValue().chunkId());
        // An incremental run never wipes the base: the other documents' entities have to survive.
        verify(graphStore, never()).deleteKb(anyString());
    }

    @Test
    void shouldDoNothingOnAnActivationWhenTheBaseDoesNotUseTheGraph() {
        when(knowledgeBaseService.graphEnabled(KB_ID)).thenReturn(false);

        service.onVersionActivated(document(), version(ACTIVE_VERSION_ID));

        verify(graphStore, never()).ensureSchema();
        verify(graphStore, never()).deleteDocumentVersions(anyString(), anyList());
        verify(kbTaskMapper, never()).insert(any(KbTask.class));
    }

    @Test
    void shouldDoNothingOnAnActivationWhenNoGraphIsConfigured() {
        when(graphStore.isEnabled()).thenReturn(false);

        service.onVersionActivated(document(), version(ACTIVE_VERSION_ID));

        verify(graphStore, never()).ensureSchema();
        verify(kbTaskMapper, never()).insert(any(KbTask.class));
    }

    @Test
    void shouldOpenOneReusableTaskPerKnowledgeBase() {
        KbTask task = service.openTask(KB_ID);

        assertEquals(TASK_ID, task.getTaskId());
        assertEquals(TaskType.GRAPH_EXTRACT, task.getTaskType());
        assertEquals(KB_ID, task.getBizId());
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        verify(kbTaskMapper).insert((KbTask) task);
    }

    @Test
    void shouldResetAndRetryTheExistingTaskRowOfTheSameKnowledgeBase() {
        KbTask existing = task();
        existing.setStatus(TaskStatus.FAILED);
        existing.setRetryCount(1);
        existing.setSkippedCount(7);
        when(kbTaskMapper.selectOne(any())).thenReturn(existing);

        KbTask task = service.openTask(KB_ID);

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(2, task.getRetryCount());
        assertEquals(0, task.getSkippedCount());
        verify(kbTaskMapper, never()).insert(any(KbTask.class));
    }

    private KbTask task() {
        KbTask task = new KbTask();
        task.setTaskId(TASK_ID);
        task.setTaskType(TaskType.GRAPH_EXTRACT);
        task.setBizId(KB_ID);
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setSkippedCount(0);
        return task;
    }

    private Chunk chunk(String chunkId, String versionId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(versionId);
        chunk.setContent("passage of " + chunkId);
        chunk.setEnabled(1);
        return chunk;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        return document;
    }

    private DocumentVersion version(String versionId) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(DOC_ID);
        return version;
    }
}
