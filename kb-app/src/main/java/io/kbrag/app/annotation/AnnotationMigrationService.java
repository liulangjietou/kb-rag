package io.kbrag.app.annotation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.document.DocumentService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.AnnotationPayloadKeys;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.InheritStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.service.AnnotationMigrationAdvisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assisted, human confirmed migration of an annotation onto a chunk of the newly activated version,
 * requirement section 4.5.
 *
 * <p><b>Why nothing migrates by itself.</b> The exact inheritance already carries every annotation whose
 * normalised text is unchanged. What is left over is, by definition, text that moved or changed, and a
 * similarity score cannot tell an edited sentence from a different one that happens to share vocabulary.
 * Applying the wrong one re-disables a passage nobody excluded or overwrites a chunk nobody reviewed - a
 * cost an operator's one click confirmation is cheap against. So this class only recommends and, when
 * asked, applies exactly what it was told to.
 *
 * <p><b>The recommendations are computed per request and never stored.</b> They depend on the version that
 * is active right now, so a cached score would be wrong the moment a new version is activated, and the
 * review list is opened rarely enough that the scan costs nothing worth caching.
 *
 * <p><b>Applying reuses the workbench.</b> A migration is not a new kind of write: it is the very toggle or
 * edit an operator would perform by hand, so it goes through {@link ChunkAnnotationService} and inherits its
 * transaction, its engine synchronisation and its audit trail. Only the review bookkeeping - closing the old
 * item - is done here.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnotationMigrationService {

    private final DocumentService documentService;
    private final AnnotationInheritanceService annotationInheritanceService;
    private final AnnotationMigrationAdvisor annotationMigrationAdvisor;
    private final AnnotationRecorder annotationRecorder;
    private final ChunkAnnotationService chunkAnnotationService;
    private final AnnotationMapper annotationMapper;
    private final ChunkMapper chunkMapper;
    private final KbProperties properties;

    /**
     * The review list of a document with a migration recommendation attached to every row.
     *
     * @param docId document business id
     * @return review items, newest first, each carrying a possibly empty recommendation list
     */
    public List<AnnotationInheritanceService.PendingAnnotation> pendingReview(String docId) {
        Document document = documentService.require(docId);
        List<AnnotationInheritanceService.PendingAnnotation> pending =
                annotationInheritanceService.pendingReview(docId, document.getCurrentVersionId());
        if (CollectionUtils.isEmpty(pending)) {
            return pending;
        }
        List<AnnotationMigrationAdvisor.Candidate> candidates =
                candidatesOf(document.getCurrentVersionId());
        if (CollectionUtils.isEmpty(candidates)) {
            return pending;
        }
        double minScore = properties.getAnnotation().getMigrationMinScore();
        List<AnnotationInheritanceService.PendingAnnotation> decorated = new ArrayList<>(pending.size());
        for (AnnotationInheritanceService.PendingAnnotation row : pending) {
            decorated.add(row.withSuggestions(
                    annotationMigrationAdvisor.suggest(row.excerpt(), candidates, minScore)));
        }
        log.info("migration suggestions computed, docId={}, pending={}, candidates={}, minScore={}",
                docId, pending.size(), candidates.size(), minScore);
        return decorated;
    }

    /**
     * Applies one annotation to a chunk of the active version and closes the review item.
     *
     * <p>Idempotent by construction rather than by a guard: replaying the same toggle changes nothing,
     * replaying the same edit leaves the normalised text equal and is skipped by the workbench, and the
     * review item is already closed. The response says which of the two happened.
     *
     * @param annotationId  annotation business id
     * @param targetChunkId chunk of the active version the annotation is applied to
     * @return what was applied and the state the annotation is now in
     */
    @Transactional(rollbackFor = Exception.class)
    public MigrationResult migrate(String annotationId, String targetChunkId) {
        Annotation annotation = requireAnnotation(annotationId);
        Chunk target = requireChunk(targetChunkId);
        if (!Objects.equals(annotation.getDocId(), target.getDocId())) {
            // Across documents a migration is not a migration but a new decision on unrelated content, and
            // the review list it would close belongs to a document the operator was not looking at.
            throw BizException.invalidParam("target_chunk_id 必须属于该标注所在的文档");
        }
        boolean alreadyMigrated = annotation.getInheritStatus() == InheritStatus.REDONE;
        List<String> changed = apply(annotation, target);
        if (!alreadyMigrated) {
            annotation.setInheritStatus(InheritStatus.REDONE);
            annotationMapper.updateById(annotation);
        }
        log.info("annotation migrated, annotationId={}, type={}, targetChunkId={}, changed={}, repeat={}",
                annotationId, annotation.getAnnotationType(), targetChunkId, changed.size(), alreadyMigrated);
        return new MigrationResult(annotationId, targetChunkId, annotation.getAnnotationType().name(),
                InheritStatus.REDONE.name(), changed, alreadyMigrated);
    }

    /**
     * Replays the operation the annotation recorded onto the target chunk.
     *
     * @param annotation annotation being migrated
     * @param target     chunk of the active version
     * @return chunk ids the replay actually changed
     */
    private List<String> apply(Annotation annotation, Chunk target) {
        return switch (annotation.getAnnotationType()) {
            case TOGGLE -> chunkAnnotationService.toggle(target.getChunkId(),
                    annotationInheritanceService.enabledOf(annotation));
            case EDIT -> applyEdit(annotation, target);
            // A merge consumed several chunks and a split produced several: neither is a statement about one
            // chunk, so there is no honest way to apply it to the single target the caller named. The review
            // item stays open and the operator repeats the operation on the boundaries of the new version.
            default -> throw BizException.invalidParam("合并与拆分标注无法迁移到单个分片，请在新版本上重新操作");
        };
    }

    /**
     * Replays an edit, refusing the case where the stored text is not the whole text.
     *
     * @param annotation edit annotation
     * @param target     chunk of the active version
     * @return chunk ids the edit changed
     */
    private List<String> applyEdit(Annotation annotation, Chunk target) {
        Object stored = annotationInheritanceService.payloadOf(annotation)
                .get(AnnotationPayloadKeys.AFTER_EXCERPT);
        String content = stored == null ? null : String.valueOf(stored);
        if (content == null || content.isBlank()) {
            throw BizException.invalidParam("该编辑标注未记录改后文本，无法迁移");
        }
        if (annotationRecorder.truncated(content)) {
            // The payload keeps an excerpt for the review list, not an archive of the text. Writing a
            // truncated excerpt back as the content of a chunk would delete the tail of a passage silently.
            throw BizException.invalidParam("该编辑标注的改后文本已截断，无法完整迁移，请手动编辑目标分片");
        }
        chunkAnnotationService.edit(target.getChunkId(), content);
        return List.of(target.getChunkId());
    }

    /**
     * Chunks of the newly activated version a migration may target.
     *
     * @param versionId active version of the document
     * @return candidates, empty when the version owns no chunk
     */
    private List<AnnotationMigrationAdvisor.Candidate> candidatesOf(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, versionId)
                .orderByAsc(Chunk::getSeq));
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        List<AnnotationMigrationAdvisor.Candidate> candidates = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            candidates.add(new AnnotationMigrationAdvisor.Candidate(chunk.getChunkId(), chunk.getContent()));
        }
        return candidates;
    }

    private Annotation requireAnnotation(String annotationId) {
        Annotation annotation = annotationMapper.selectOne(new LambdaQueryWrapper<Annotation>()
                .eq(Annotation::getAnnotationId, annotationId)
                .last("limit 1"));
        if (annotation == null) {
            throw BizException.notFound("annotation not found");
        }
        return annotation;
    }

    private Chunk requireChunk(String chunkId) {
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getChunkId, chunkId)
                .last("limit 1"));
        if (chunk == null) {
            throw BizException.notFound("chunk not found");
        }
        return chunk;
    }

    /**
     * Outcome of one migration.
     *
     * @param annotationId    annotation that was migrated
     * @param targetChunkId   chunk it was applied to
     * @param annotationType  operation kind that was replayed
     * @param inheritStatus   state the annotation is in afterwards, always {@code REDONE}
     * @param changedChunkIds chunks the replay actually modified, empty on a repeated call
     * @param alreadyMigrated {@code true} when the review item was already closed before this call
     */
    public record MigrationResult(String annotationId, String targetChunkId, String annotationType,
                                  String inheritStatus, List<String> changedChunkIds,
                                  boolean alreadyMigrated) {
    }
}
