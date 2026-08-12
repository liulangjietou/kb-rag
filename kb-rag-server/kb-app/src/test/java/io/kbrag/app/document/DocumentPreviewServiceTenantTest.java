package io.kbrag.app.document;

import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.port.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the tenant resolution of the one entry of this service that is addressed by a knowledge base
 * id rather than by a document id.
 *
 * <p>{@code POST /kb/{kbId}/documents/confirm} confirms every document of a base that is waiting for
 * a parse confirmation. Nothing in the statement it issues mentions a tenant - {@code t_kb_document}
 * has no such column - so the fenced read of the base is the only thing that keeps the batch from
 * pushing another tenant's pending documents into the index pipeline.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentPreviewServiceTenantTest {

    private static final String KB_ID = "kb_alpha";
    private static final String DOC_ID = "doc_1";

    private DocumentMapper documentMapper;
    private IndexPipelineService indexPipelineService;
    private KnowledgeBaseService knowledgeBaseService;
    private DocumentPreviewService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class);
        documentMapper = mock(DocumentMapper.class);
        indexPipelineService = mock(IndexPipelineService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        service = new DocumentPreviewService(documentMapper, mock(DocumentVersionMapper.class),
                mock(ObjectStorage.class), indexPipelineService, new KbProperties(),
                knowledgeBaseService);
    }

    @Test
    void shouldRefuseToConfirmTheDocumentsOfAnotherTenantsBase() {
        when(knowledgeBaseService.require(anyString()))
                .thenThrow(BizException.notFound("knowledge base not found"));

        assertThrows(BizException.class, () -> service.confirmAll(KB_ID, List.of(DOC_ID)));
        assertThrows(BizException.class, () -> service.confirmAll(KB_ID, null));

        verify(documentMapper, never()).selectList(any());
        verify(indexPipelineService, never()).submitConfirm(anyString());
    }
}
