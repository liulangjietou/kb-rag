package io.kbrag.app.index;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the catch-up status the console renders instead of remembering what it submitted.
 *
 * <p>The three counts are read from three queries over the same stale scope, so the risk worth a test is
 * not the SQL - it is the mapping: an in-progress count reported as the failed one would tell an operator
 * a running rebuild has broken, and a stale count that includes documents {@link RebuildService#submit}
 * refuses to queue would leave the warning banner up forever.
 *
 * @author owlzhangfq@gmail.com
 */
class RebuildServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String VERSION_ID = "dv_test";

    private DocumentMapper documentMapper;
    private DocumentService documentService;
    private IndexPipelineService indexPipelineService;
    private KnowledgeBaseService knowledgeBaseService;
    private RebuildService rebuildService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentService = mock(DocumentService.class);
        indexPipelineService = mock(IndexPipelineService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        rebuildService = new RebuildService(documentMapper, documentService, indexPipelineService,
                knowledgeBaseService);
    }

    @Test
    void shouldReportStaleInProgressAndFailedCountsInThatOrder() {
        // Stale is the whole outstanding set; in-progress and failed are subsets of it.
        when(documentMapper.selectCount(any())).thenReturn(5L, 2L, 1L);

        RebuildService.RebuildStatus status = rebuildService.status(KB_ID);

        assertEquals(5, status.staleCount());
        assertEquals(2, status.inProgressCount());
        assertEquals(1, status.failedCount());
    }

    @Test
    void shouldQueueEveryStaleDocumentThatHasAnActiveVersion() {
        Document withVersion = new Document();
        withVersion.setDocId("doc_1");
        withVersion.setCurrentVersionId(VERSION_ID);
        // 没有活跃版本就没有可重建的内容：既不该提交，也不该被 status 计入分母。
        Document withoutVersion = new Document();
        withoutVersion.setDocId("doc_2");
        when(documentMapper.selectList(any())).thenReturn(List.of(withVersion, withoutVersion));

        List<String> queued = rebuildService.submit(KB_ID, null);

        assertEquals(List.of("doc_1"), queued);
        verify(indexPipelineService).submitRebuild(VERSION_ID);
        verify(documentService, never()).requireAllInKb(any(), any());
    }

    @Test
    void shouldRefuseToRebuildOrReportOnAnotherTenantsBase() {
        when(knowledgeBaseService.require(KB_ID))
                .thenThrow(BizException.notFound("knowledge base not found"));

        // 一次跨租户重建就是替别家烧一遍算力，并把它们的索引换成本次配置的产物。
        assertThrows(BizException.class, () -> rebuildService.submit(KB_ID, null));
        assertThrows(BizException.class, () -> rebuildService.status(KB_ID));
        verify(indexPipelineService, never()).submitRebuild(any());
        verify(documentMapper, never()).selectList(any());
    }
}
