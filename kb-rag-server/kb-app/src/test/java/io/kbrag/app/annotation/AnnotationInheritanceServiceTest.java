package io.kbrag.app.annotation;

import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.AnnotationPayloadKeys;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.InheritStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.service.ChunkTextHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the cross version behaviour of annotations: a disable follows text that is byte identical,
 * everything else surfaces as a review item, and the switch can turn the inheritance off entirely.
 *
 * @author owlzhangfq@gmail.com
 */
class AnnotationInheritanceServiceTest {

    private static final String KB_ID = "kb_1";
    private static final String DOC_ID = "doc_1";
    private static final String OLD_VERSION_ID = "dv_old";
    private static final String NEW_VERSION_ID = "dv_new";
    private static final String DISABLED_TEXT = "the paragraph an operator excluded";
    private static final String OTHER_TEXT = "an unrelated paragraph";

    private final ChunkTextHasher hasher = new ChunkTextHasher();

    private ChunkMapper chunkMapper;
    private AnnotationMapper annotationMapper;
    private DocumentVersionMapper documentVersionMapper;
    private ChunkIndexWriter chunkIndexWriter;
    private KnowledgeBaseService knowledgeBaseService;
    private AnnotationRecorder annotationRecorder;
    private AnnotationInheritanceService service;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(ChunkMapper.class);
        annotationMapper = mock(AnnotationMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        chunkIndexWriter = mock(ChunkIndexWriter.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        annotationRecorder = mock(AnnotationRecorder.class);

        when(knowledgeBaseService.indexConfigOf(anyString())).thenReturn(indexConfig(true));
        service = new AnnotationInheritanceService(chunkMapper, annotationMapper, documentVersionMapper,
                chunkIndexWriter, knowledgeBaseService, annotationRecorder);
    }

    @Test
    void shouldInheritADisableOntoAChunkWithTheIdenticalText() {
        when(annotationMapper.selectList(any())).thenReturn(List.of(disableAnnotation(1L, DISABLED_TEXT)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_new_a", null, DISABLED_TEXT),
                chunk("ck_new_b", null, OTHER_TEXT)));

        List<String> disabled = service.inherit(document(), newVersion());

        assertEquals(List.of("ck_new_a"), disabled);
        verify(chunkMapper, times(1)).updateById(any(Chunk.class));
        verify(chunkIndexWriter, times(1)).syncEnabled(KB_ID, List.of("ck_new_a"), false);
        verify(annotationRecorder, times(1)).recordInherited(any(), any());
    }

    @Test
    void shouldNotInheritWhenTheTextWasEdited() {
        // Exact matching only: a chunk whose wording moved is different knowledge, and carrying a disable
        // onto it would silently remove a passage nobody excluded.
        when(annotationMapper.selectList(any())).thenReturn(List.of(disableAnnotation(1L, DISABLED_TEXT)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_new_a", null, DISABLED_TEXT + " with an addition")));

        assertTrue(service.inherit(document(), newVersion()).isEmpty());
        verify(chunkIndexWriter, never()).syncEnabled(anyString(), any(), eq(false));
    }

    @Test
    void shouldIgnoreADisableThatWasLaterUndone() {
        // The trail holds every toggle, so replaying them in order is what leaves the last decision standing.
        Annotation disabled = disableAnnotation(1L, DISABLED_TEXT);
        Annotation enabledAgain = toggleAnnotation(2L, DISABLED_TEXT, true);
        when(annotationMapper.selectList(any())).thenReturn(List.of(disabled, enabledAgain));

        assertTrue(service.inherit(document(), newVersion()).isEmpty());
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    void shouldCascadeAnInheritedParentDisableOntoItsChildren() {
        // A parent carries one annotation for the whole cascade, so its children are not matched by hash and
        // have to follow their parent exactly as they do on a manual disable.
        when(annotationMapper.selectList(any())).thenReturn(List.of(disableAnnotation(1L, DISABLED_TEXT)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_new_parent", null, DISABLED_TEXT),
                chunk("ck_new_child", "ck_new_parent", OTHER_TEXT)));

        List<String> disabled = service.inherit(document(), newVersion());

        assertEquals(List.of("ck_new_parent", "ck_new_child"), disabled);
    }

    @Test
    void shouldInheritNothingWhenTheSwitchIsOff() {
        when(knowledgeBaseService.indexConfigOf(anyString())).thenReturn(indexConfig(false));

        assertTrue(service.inherit(document(), newVersion()).isEmpty());
        verify(annotationMapper, never()).selectList(any());
    }

    @Test
    void shouldListOnlyTheAnnotationsTheTargetVersionDoesNotCarry() {
        Annotation openEdit = annotation(1L, OLD_VERSION_ID, AnnotationType.EDIT, OTHER_TEXT,
                InheritStatus.NOT_INHERITED);
        Annotation redoneEdit = annotation(2L, OLD_VERSION_ID, AnnotationType.EDIT, DISABLED_TEXT,
                InheritStatus.NOT_INHERITED);
        Annotation counterpart = annotation(3L, NEW_VERSION_ID, AnnotationType.EDIT, DISABLED_TEXT,
                InheritStatus.NOT_INHERITED);
        Annotation autoInherited = annotation(4L, OLD_VERSION_ID, AnnotationType.TOGGLE, OTHER_TEXT,
                InheritStatus.AUTO_INHERITED);
        Annotation alreadyRedone = annotation(5L, OLD_VERSION_ID, AnnotationType.SPLIT, OTHER_TEXT,
                InheritStatus.REDONE);
        when(annotationMapper.selectList(any())).thenReturn(
                List.of(openEdit, redoneEdit, counterpart, autoInherited, alreadyRedone));
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(oldVersionRow()));

        List<AnnotationInheritanceService.PendingAnnotation> pending =
                service.pendingReview(DOC_ID, NEW_VERSION_ID);

        assertEquals(1, pending.size());
        AnnotationInheritanceService.PendingAnnotation item = pending.get(0);
        assertEquals("an_1", item.annotationId());
        assertEquals("1.0", item.version());
        // The whole stored row travels to the console, not just the identifiers it was filtered by: the
        // review list renders the inheritance state as a tag and the excerpt as the recognisable text.
        assertEquals(KB_ID, item.kbId());
        assertEquals(DOC_ID, item.docId());
        assertEquals(OLD_VERSION_ID, item.documentVersionId());
        assertEquals(AnnotationType.EDIT.name(), item.annotationType());
        assertEquals(InheritStatus.NOT_INHERITED.name(), item.inheritStatus());
        assertEquals(hasher.hash(OTHER_TEXT), item.chunkTextHash());
        assertEquals(KbConstants.ANNOTATION_OPERATOR_ADMIN, item.operator());
        assertEquals(OTHER_TEXT, item.excerpt());
        assertEquals(1, service.staleCount(DOC_ID, NEW_VERSION_ID));
    }

    @Test
    void shouldReportNoReviewItemsForADocumentWithoutAnnotations() {
        when(annotationMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.pendingReview(DOC_ID, NEW_VERSION_ID).isEmpty());
        assertEquals(0, service.staleCount(DOC_ID, NEW_VERSION_ID));
    }

    private KbIndexConfig indexConfig(boolean inherit) {
        KbIndexConfig config = new KbIndexConfig();
        config.setChunkMaxTokens(600);
        config.setChunkOverlap(100);
        config.setInheritDisableAnnotation(inherit);
        return config;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setCurrentVersionId(NEW_VERSION_ID);
        return document;
    }

    private DocumentVersion newVersion() {
        DocumentVersion version = new DocumentVersion();
        version.setId(2L);
        version.setVersionId(NEW_VERSION_ID);
        version.setDocId(DOC_ID);
        version.setVersion("2.0");
        version.setStatus(DocumentVersionStatus.ACTIVE);
        return version;
    }

    private DocumentVersion oldVersionRow() {
        DocumentVersion version = new DocumentVersion();
        version.setId(1L);
        version.setVersionId(OLD_VERSION_ID);
        version.setDocId(DOC_ID);
        version.setVersion("1.0");
        version.setStatus(DocumentVersionStatus.READY);
        return version;
    }

    private Annotation disableAnnotation(long id, String text) {
        return toggleAnnotation(id, text, false);
    }

    private Annotation toggleAnnotation(long id, String text, boolean enabled) {
        Annotation annotation = annotation(id, OLD_VERSION_ID, AnnotationType.TOGGLE, text,
                InheritStatus.NOT_INHERITED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AnnotationPayloadKeys.ENABLED, enabled);
        payload.put(AnnotationPayloadKeys.EXCERPT, text);
        annotation.setPayload(JsonUtil.toJson(payload));
        return annotation;
    }

    private Annotation annotation(long id, String versionId, AnnotationType type, String text,
                                  InheritStatus inheritStatus) {
        Annotation annotation = new Annotation();
        annotation.setId(id);
        annotation.setAnnotationId("an_" + id);
        annotation.setKbId(KB_ID);
        annotation.setDocId(DOC_ID);
        annotation.setDocumentVersionId(versionId);
        annotation.setChunkId("ck_" + id);
        annotation.setAnnotationType(type);
        annotation.setChunkTextHash(hasher.hash(text));
        annotation.setInheritStatus(inheritStatus);
        annotation.setOperator(KbConstants.ANNOTATION_OPERATOR_ADMIN);
        annotation.setPayload(JsonUtil.toJson(Map.of(AnnotationPayloadKeys.EXCERPT, text)));
        return annotation;
    }

    private Chunk chunk(String chunkId, String parentId, String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(NEW_VERSION_ID);
        chunk.setContent(content);
        chunk.setChunkTextHash(hasher.hash(content));
        chunk.setParentId(parentId);
        chunk.setSeq(0);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        return chunk;
    }
}
