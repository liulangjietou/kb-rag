package io.kbrag.app.graph;

import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.GraphEntityChunkRef;
import io.kbrag.domain.model.GraphSummary;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.GraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the management surface of the graph: the trigger's refusals, the summary the console polls and
 * the entity drill down that resolves everything but the chunk id from the MySQL fact source.
 *
 * @author owlzhangfq@gmail.com
 */
class GraphAdminServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String DOC_ID = "doc_test";
    private static final String VERSION_ID = "dv_active";
    private static final String TASK_ID = "task_graph";
    private static final String ENTITY = "苹果公司";
    private static final int LIMIT = 100;

    private KnowledgeBaseService knowledgeBaseService;
    private GraphExtractionService graphExtractionService;
    private GraphStore graphStore;
    private ChunkMapper chunkMapper;
    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private KbTaskMapper kbTaskMapper;
    private GraphAdminService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        graphExtractionService = mock(GraphExtractionService.class);
        graphStore = mock(GraphStore.class);
        chunkMapper = mock(ChunkMapper.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        kbTaskMapper = mock(KbTaskMapper.class);

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase);
        when(graphStore.isEnabled()).thenReturn(true);
        when(knowledgeBaseService.graphEnabled(KB_ID)).thenReturn(true);

        service = new GraphAdminService(knowledgeBaseService, graphExtractionService, graphStore,
                chunkMapper, documentMapper, documentVersionMapper, kbTaskMapper);
    }

    @Test
    void shouldRefuseTheTriggerWhenNoGraphIsConfigured() {
        when(graphStore.isEnabled()).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> service.triggerExtraction(KB_ID));

        assertEquals(ErrorCode.INVALID_PARAM, exception.getErrorCode());
        verify(graphExtractionService, never()).openTask(anyString());
    }

    @Test
    void shouldRefuseTheTriggerWhenTheBaseDidNotEnableTheGraph() {
        when(knowledgeBaseService.graphEnabled(KB_ID)).thenReturn(false);

        assertThrows(BizException.class, () -> service.triggerExtraction(KB_ID));
        verify(graphExtractionService, never()).openTask(anyString());
    }

    @Test
    void shouldOpenTheTaskOnTheRequestThreadAndRunItAsynchronously() {
        KbTask task = new KbTask();
        task.setTaskId(TASK_ID);
        when(graphExtractionService.openTask(KB_ID)).thenReturn(task);

        assertEquals(TASK_ID, service.triggerExtraction(KB_ID));

        // The row exists before the answer leaves, otherwise the console would poll an id nothing created.
        verify(graphExtractionService).openTask(KB_ID);
        verify(graphExtractionService).runFullExtraction(KB_ID, task);
    }

    @Test
    void shouldReportTheSwitchTheCountsAndTheLatestTask() {
        KbRetrievalConfig config = new KbRetrievalConfig();
        config.setGraphEnabled(true);
        when(knowledgeBaseService.retrievalConfigOf(any(KnowledgeBase.class))).thenReturn(config);
        when(graphStore.summary(KB_ID)).thenReturn(new GraphSummary(12L, 7L, 30L));
        KbTask task = new KbTask();
        task.setTaskId(TASK_ID);
        task.setTaskType(TaskType.GRAPH_EXTRACT);
        task.setStatus(TaskStatus.SUCCESS);
        task.setSkippedCount(3);
        when(kbTaskMapper.selectOne(any())).thenReturn(task);

        GraphSummaryView view = service.summary(KB_ID);

        assertTrue(view.graphEnabled());
        assertEquals(12L, view.counts().entityCount());
        assertEquals(7L, view.counts().relationCount());
        assertEquals(30L, view.counts().coveredChunkCount());
        assertEquals(3, view.latestTask().getSkippedCount());
    }

    @Test
    void shouldReportNoTaskWhenNoExtractionEverRan() {
        when(knowledgeBaseService.retrievalConfigOf(any(KnowledgeBase.class)))
                .thenReturn(new KbRetrievalConfig());
        when(graphStore.summary(KB_ID)).thenReturn(GraphSummary.EMPTY);
        when(kbTaskMapper.selectOne(any())).thenReturn(null);

        GraphSummaryView view = service.summary(KB_ID);

        assertFalse(view.graphEnabled());
        assertNull(view.latestTask());
        assertEquals(0L, view.counts().entityCount());
    }

    @Test
    void shouldTranslateThePageNumberIntoAnOffset() {
        when(graphStore.listEntities(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());

        service.listEntities(KB_ID, "苹果", 3, 20);

        verify(graphStore).listEntities(eq(KB_ID), eq("苹果"), eq(40), eq(20));
    }

    @Test
    void shouldResolveTheDocumentNameAndVersionLabelOfEveryDrillDownRow() {
        when(graphStore.chunksOf(KB_ID, ENTITY, LIMIT))
                .thenReturn(List.of(new GraphEntityChunkRef("ck_1", VERSION_ID)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", 1)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version()));

        List<GraphEntityChunkView> views = service.chunksOf(KB_ID, ENTITY, LIMIT);

        assertEquals(1, views.size());
        GraphEntityChunkView view = views.get(0);
        assertEquals("ck_1", view.chunkId());
        assertEquals(DOC_ID, view.docId());
        assertEquals("handbook.pdf", view.docFileName());
        assertEquals(VERSION_ID, view.documentVersionId());
        assertEquals("v3", view.documentVersionLabel());
        assertEquals("passage text", view.content());
        assertTrue(view.enabled());
    }

    @Test
    void shouldReportADisabledPassageRatherThanHidingIt() {
        when(graphStore.chunksOf(KB_ID, ENTITY, LIMIT))
                .thenReturn(List.of(new GraphEntityChunkRef("ck_1", VERSION_ID)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_1", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version()));

        // The drill down is what an operator uses to understand why the graph route skipped a passage, so
        // the disabled flag has to be visible rather than the row being dropped.
        assertFalse(service.chunksOf(KB_ID, ENTITY, LIMIT).get(0).enabled());
    }

    @Test
    void shouldDropAGraphPointerTheFactSourceNoLongerOwns() {
        when(graphStore.chunksOf(KB_ID, ENTITY, LIMIT)).thenReturn(List.of(
                new GraphEntityChunkRef("ck_live", VERSION_ID),
                new GraphEntityChunkRef("ck_gone", VERSION_ID)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("ck_live", 1)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version()));

        List<GraphEntityChunkView> views = service.chunksOf(KB_ID, ENTITY, LIMIT);

        assertEquals(List.of("ck_live"), views.stream().map(GraphEntityChunkView::chunkId).toList());
    }

    @Test
    void shouldReturnNothingWhenTheEntityTracesBackToNoChunk() {
        when(graphStore.chunksOf(KB_ID, ENTITY, LIMIT)).thenReturn(List.of());

        assertTrue(service.chunksOf(KB_ID, ENTITY, LIMIT).isEmpty());
        verify(chunkMapper, never()).selectList(any());
    }

    private Chunk chunk(String chunkId, int enabled) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setContent("passage text");
        chunk.setEnabled(enabled);
        return chunk;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setFileName("handbook.pdf");
        return document;
    }

    private DocumentVersion version() {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(VERSION_ID);
        version.setDocId(DOC_ID);
        version.setVersion("v3");
        return version;
    }
}
