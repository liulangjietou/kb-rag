package io.kbrag.app.annotation;

import io.kbrag.app.document.DocumentService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.AnnotationPayloadKeys;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.InheritStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.service.AnnotationMigrationAdvisor;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the assisted migration of requirement section 4.5: what the review list recommends, that applying
 * a recommendation goes through the ordinary workbench write path, that repeating the call changes nothing,
 * and that the operations which cannot be expressed against a single chunk are refused instead of guessed.
 *
 * @author owlzhangfq@gmail.com
 */
class AnnotationMigrationServiceTest {

    private static final String DOC_ID = "doc_1";
    private static final String KB_ID = "kb_1";
    private static final String OLD_VERSION_ID = "dv_1";
    private static final String ACTIVE_VERSION_ID = "dv_2";
    private static final String ANNOTATION_ID = "an_1";
    private static final String TARGET_CHUNK_ID = "ck_target";
    private static final String EXCERPT = "知识库检索需要把文档切成合适的片段";

    private DocumentService documentService;
    private AnnotationInheritanceService annotationInheritanceService;
    private ChunkAnnotationService chunkAnnotationService;
    private AnnotationMapper annotationMapper;
    private ChunkMapper chunkMapper;
    private AnnotationMigrationService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        annotationInheritanceService = mock(AnnotationInheritanceService.class);
        chunkAnnotationService = mock(ChunkAnnotationService.class);
        annotationMapper = mock(AnnotationMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        when(documentService.require(DOC_ID)).thenReturn(document());
        service = new AnnotationMigrationService(documentService, annotationInheritanceService,
                new AnnotationMigrationAdvisor(new ChunkTextHasher()),
                new AnnotationRecorder(mock(AnnotationMapper.class), mock(BizIdGenerator.class)),
                chunkAnnotationService, annotationMapper, chunkMapper, new KbProperties());
    }

    @Test
    void shouldAttachTheBestCandidatesOfTheActiveVersionToEveryReviewRow() {
        givenPending(pending(EXCERPT));
        givenActiveVersionChunks(chunk(TARGET_CHUNK_ID, EXCERPT), chunk("ck_other", "应用中心的发布门禁"));

        List<AnnotationInheritanceService.PendingAnnotation> rows = service.pendingReview(DOC_ID);

        assertEquals(1, rows.size());
        assertEquals(List.of(TARGET_CHUNK_ID), rows.get(0).suggestions().stream()
                .map(AnnotationMigrationAdvisor.Suggestion::chunkId).toList());
        assertEquals(1.0d, rows.get(0).suggestions().get(0).score());
        assertEquals(EXCERPT, rows.get(0).suggestions().get(0).contentPreview());
    }

    @Test
    void shouldStillReturnAListWhenNothingIsSimilarEnough() {
        givenPending(pending(EXCERPT));
        givenActiveVersionChunks(chunk("ck_other", "应用中心的发布门禁与快照保留策略"));

        List<AnnotationInheritanceService.PendingAnnotation> rows = service.pendingReview(DOC_ID);

        // Always a list and never a missing field: a console binding it must not have to branch.
        assertEquals(List.of(), rows.get(0).suggestions());
    }

    @Test
    void shouldGiveNoSuggestionWhenTheActiveVersionOwnsNoChunk() {
        givenPending(pending(EXCERPT));
        givenActiveVersionChunks();

        assertEquals(List.of(), service.pendingReview(DOC_ID).get(0).suggestions());
    }

    @Test
    void shouldApplyADisableAnnotationToTheTargetAndCloseTheReviewItem() {
        Annotation annotation = toggleAnnotation(false);
        givenAnnotation(annotation);
        givenTargetChunk(chunk(TARGET_CHUNK_ID, EXCERPT));
        when(annotationInheritanceService.enabledOf(annotation)).thenReturn(false);
        when(chunkAnnotationService.toggle(TARGET_CHUNK_ID, false)).thenReturn(List.of(TARGET_CHUNK_ID));

        AnnotationMigrationService.MigrationResult result =
                service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID);

        verify(chunkAnnotationService).toggle(TARGET_CHUNK_ID, false);
        assertEquals(List.of(TARGET_CHUNK_ID), result.changedChunkIds());
        assertEquals(InheritStatus.REDONE.name(), result.inheritStatus());
        assertFalse(result.alreadyMigrated());
        // Closing the item here rather than deriving it on read is what keeps the review count and the
        // review list from ever disagreeing.
        assertEquals(InheritStatus.REDONE, annotation.getInheritStatus());
        verify(annotationMapper).updateById(annotation);
    }

    @Test
    void shouldReportTheCurrentStateWhenTheSameMigrationIsRepeated() {
        Annotation annotation = toggleAnnotation(false);
        annotation.setInheritStatus(InheritStatus.REDONE);
        givenAnnotation(annotation);
        givenTargetChunk(chunk(TARGET_CHUNK_ID, EXCERPT));
        when(annotationInheritanceService.enabledOf(annotation)).thenReturn(false);
        // The chunk is already disabled, so the workbench reports that nothing changed.
        when(chunkAnnotationService.toggle(TARGET_CHUNK_ID, false)).thenReturn(List.of());

        AnnotationMigrationService.MigrationResult result =
                service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID);

        assertTrue(result.alreadyMigrated());
        assertEquals(List.of(), result.changedChunkIds());
        assertEquals(InheritStatus.REDONE.name(), result.inheritStatus());
        verify(annotationMapper, never()).updateById(any(Annotation.class));
    }

    @Test
    void shouldReplayAnEditWhoseTextWasStoredInFull() {
        Annotation annotation = editAnnotation("改写后的完整段落");
        givenAnnotation(annotation);
        givenTargetChunk(chunk(TARGET_CHUNK_ID, EXCERPT));
        when(annotationInheritanceService.payloadOf(annotation))
                .thenReturn(Map.of(AnnotationPayloadKeys.AFTER_EXCERPT, "改写后的完整段落"));

        AnnotationMigrationService.MigrationResult result =
                service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID);

        verify(chunkAnnotationService).edit(TARGET_CHUNK_ID, "改写后的完整段落");
        assertEquals(List.of(TARGET_CHUNK_ID), result.changedChunkIds());
    }

    @Test
    void shouldRefuseToReplayAnEditWhoseTextWasTruncatedIntoTheExcerpt() {
        String truncated = "改".repeat(500) + "...";
        Annotation annotation = editAnnotation(truncated);
        givenAnnotation(annotation);
        givenTargetChunk(chunk(TARGET_CHUNK_ID, EXCERPT));
        when(annotationInheritanceService.payloadOf(annotation))
                .thenReturn(Map.of(AnnotationPayloadKeys.AFTER_EXCERPT, truncated));

        BizException failure = assertThrows(BizException.class,
                () -> service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID));

        // The payload stores an excerpt for the review list, not an archive of the text.
        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        verify(chunkAnnotationService, never()).edit(anyString(), anyString());
    }

    @Test
    void shouldRefuseToMigrateAMergeOrASplit() {
        for (AnnotationType type : List.of(AnnotationType.MERGE, AnnotationType.SPLIT)) {
            Annotation annotation = toggleAnnotation(false);
            annotation.setAnnotationType(type);
            givenAnnotation(annotation);
            givenTargetChunk(chunk(TARGET_CHUNK_ID, EXCERPT));

            BizException failure = assertThrows(BizException.class,
                    () -> service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID));

            assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        }
    }

    @Test
    void shouldRefuseATargetThatBelongsToAnotherDocument() {
        givenAnnotation(toggleAnnotation(false));
        Chunk foreign = chunk(TARGET_CHUNK_ID, EXCERPT);
        foreign.setDocId("doc_other");
        givenTargetChunk(foreign);

        BizException failure = assertThrows(BizException.class,
                () -> service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldFailLoudlyForAnUnknownAnnotationOrChunk() {
        when(annotationMapper.selectOne(any())).thenReturn(null);

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID)).getErrorCode());

        givenAnnotation(toggleAnnotation(false));
        when(chunkMapper.selectOne(any())).thenReturn(null);

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.migrate(ANNOTATION_ID, TARGET_CHUNK_ID)).getErrorCode());
    }

    private void givenPending(AnnotationInheritanceService.PendingAnnotation... rows) {
        when(annotationInheritanceService.pendingReview(DOC_ID, ACTIVE_VERSION_ID))
                .thenReturn(List.of(rows));
    }

    private void givenActiveVersionChunks(Chunk... chunks) {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunks));
    }

    private void givenAnnotation(Annotation annotation) {
        when(annotationMapper.selectOne(any())).thenReturn(annotation);
    }

    private void givenTargetChunk(Chunk chunk) {
        when(chunkMapper.selectOne(any())).thenReturn(chunk);
    }

    private Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setCurrentVersionId(ACTIVE_VERSION_ID);
        return document;
    }

    private AnnotationInheritanceService.PendingAnnotation pending(String excerpt) {
        return new AnnotationInheritanceService.PendingAnnotation(ANNOTATION_ID, KB_ID, DOC_ID,
                OLD_VERSION_ID, "v1", "ck_old", AnnotationType.TOGGLE.name(), Map.of(), excerpt,
                "hash", InheritStatus.NOT_INHERITED.name(), "admin", "2026-07-27T00:00:00", List.of());
    }

    private Annotation toggleAnnotation(boolean enabled) {
        Annotation annotation = new Annotation();
        annotation.setAnnotationId(ANNOTATION_ID);
        annotation.setKbId(KB_ID);
        annotation.setDocId(DOC_ID);
        annotation.setDocumentVersionId(OLD_VERSION_ID);
        annotation.setChunkId("ck_old");
        annotation.setAnnotationType(AnnotationType.TOGGLE);
        annotation.setInheritStatus(InheritStatus.NOT_INHERITED);
        annotation.setPayload(JsonUtil.toJson(Map.of(AnnotationPayloadKeys.ENABLED, enabled)));
        return annotation;
    }

    private Annotation editAnnotation(String afterExcerpt) {
        Annotation annotation = toggleAnnotation(true);
        annotation.setAnnotationType(AnnotationType.EDIT);
        annotation.setPayload(JsonUtil.toJson(Map.of(AnnotationPayloadKeys.AFTER_EXCERPT, afterExcerpt)));
        return annotation;
    }

    private Chunk chunk(String chunkId, String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(ACTIVE_VERSION_ID);
        chunk.setContent(content);
        chunk.setSeq(0);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        return chunk;
    }
}
