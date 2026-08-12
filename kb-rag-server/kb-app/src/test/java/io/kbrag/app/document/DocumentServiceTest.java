package io.kbrag.app.document;

import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.VersionFingerprintFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the governance side of the intake: the initial publication state is decided once, by the
 * review switch of the knowledge base at creation time, and later versions inherit it.
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentServiceTest {

    private static final String KB_ID = "kb_1";
    private static final String DOC_ID = "doc_1";
    private static final String VERSION_ID = "dv_1";
    private static final String FILE_NAME = "guide.txt";
    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private DocumentVersionPlanner versionPlanner;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Document.class, DocumentVersion.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        versionPlanner = mock(DocumentVersionPlanner.class);
        UploadValidator uploadValidator = mock(UploadValidator.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        service = new DocumentService(documentMapper, documentVersionMapper,
                mock(ChunkMapper.class), mock(AnnotationMapper.class), mock(DocAclMapper.class),
                mock(ObjectStorage.class),
                bizIdGenerator, uploadValidator, knowledgeBaseService,
                mock(IndexPipelineService.class), versionPlanner,
                mock(VersionFingerprintFactory.class), mock(EmbeddingProvider.class),
                mock(VisionProvider.class));

        when(uploadValidator.validate(anyString(), any())).thenReturn("txt");
        when(bizIdGenerator.documentId()).thenReturn(DOC_ID);
        when(bizIdGenerator.documentVersionId()).thenReturn(VERSION_ID);
        when(versionPlanner.plan(any(), any())).thenReturn(new DocumentVersionPlanner.VersionPlan(
                false, "1", null, DocumentVersionPlanner.Reuse.none()));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void shouldPublishANewDocumentWhenTheKnowledgeBaseNeedsNoReview() {
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(0));

        service.upload(KB_ID, FILE_NAME, CONTENT);

        Document inserted = capturedInsert();
        assertEquals(PublishStatus.PUBLISHED, inserted.getPublishStatus());
        assertEquals(0, inserted.getTrashed());
    }

    @Test
    void shouldStartANewDocumentAsADraftUnderAReviewRequiringKnowledgeBase() {
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(1));

        service.upload(KB_ID, FILE_NAME, CONTENT);

        assertEquals(PublishStatus.DRAFT, capturedInsert().getPublishStatus());
    }

    @Test
    void shouldNotResetThePublicationStateWhenAVersionIsAdded() {
        // The review gates what the document is, not each revision: a re-upload of a draft must not
        // silently publish it, and a re-upload of a published document must not pull it offline.
        when(knowledgeBaseService.require(KB_ID)).thenReturn(knowledgeBase(1));
        Document existing = new Document();
        existing.setDocId(DOC_ID);
        existing.setKbId(KB_ID);
        existing.setFileName(FILE_NAME);
        existing.setPublishStatus(PublishStatus.DRAFT);
        existing.setTrashed(0);
        when(documentMapper.selectOne(any())).thenReturn(existing);

        service.upload(KB_ID, FILE_NAME, CONTENT);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).updateById(captor.capture());
        assertEquals(PublishStatus.DRAFT, captor.getValue().getPublishStatus());
    }

    @Test
    void shouldRefuseEveryKbScopedEntryOfAnotherTenantsBase() {
        // t_kb_document carries no tenant_id. Naming a base is therefore not proof of anything until
        // that base is read back through the fence, which is what require() does on a console thread.
        when(knowledgeBaseService.require(KB_ID))
                .thenThrow(BizException.notFound("knowledge base not found"));

        assertThrows(BizException.class, () -> service.list(KB_ID, null, 1, 20));
        assertThrows(BizException.class, () -> service.reindexAll(KB_ID, List.of(DOC_ID)));
        assertThrows(BizException.class, () -> service.requireAllInKb(KB_ID, List.of(DOC_ID)));

        // 批量删除与批量重建共用 requireAllInKb，所以这一处拦住就两个入口都拦住了。
        verify(documentMapper, never()).selectPage(any(), any());
        verify(documentMapper, never()).selectList(any());
    }

    private Document capturedInsert() {
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        return captor.getValue();
    }

    private KnowledgeBase knowledgeBase(int reviewRequired) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        knowledgeBase.setReviewRequired(reviewRequired);
        return knowledgeBase;
    }
}
