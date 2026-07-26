package io.kbrag.app.annotation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.AnnotationPayloadKeys;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.InheritStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries the disable annotations of a document across its versions, and reports what could not be
 * carried.
 *
 * <p><b>Why only the disable annotations are inherited.</b> A new version may cut the document
 * differently, so replaying an edit, a merge or a split would apply an operator decision to text nobody
 * approved. Excluding a chunk, on the other hand, is a statement about the text itself: as long as the
 * normalised text of a chunk is unchanged, the decision to keep it out of retrieval is still valid, and
 * losing it on every re-upload would silently re-expose content somebody removed on purpose. Matching is
 * exact - no similarity heuristic - which is what keeps the inheritance from ever guessing.
 *
 * <p><b>Everything else becomes a review item.</b> The pending list is the honest half of that decision:
 * the operator is told which of their earlier operations the new version does not carry, with the
 * original excerpt, so the ones that still matter can be performed again.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnotationInheritanceService {

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final ChunkMapper chunkMapper;
    private final AnnotationMapper annotationMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkIndexWriter chunkIndexWriter;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnnotationRecorder annotationRecorder;

    /**
     * Applies the inherited disable state to a version that has just become active.
     *
     * @param document owning document
     * @param version  version that was just activated
     * @return chunk ids of the new version that were disabled by inheritance
     */
    public List<String> inherit(Document document, DocumentVersion version) {
        if (!knowledgeBaseService.indexConfigOf(document.getKbId()).isInheritDisableAnnotation()) {
            log.info("disable annotation inheritance switched off, docId={}, versionId={}",
                    document.getDocId(), version.getVersionId());
            return List.of();
        }
        Map<String, String> sourceByHash = disableSourceByHash(document.getDocId(), version.getVersionId());
        if (sourceByHash.isEmpty()) {
            return List.of();
        }
        List<Chunk> versionChunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, version.getVersionId()));
        if (CollectionUtils.isEmpty(versionChunks)) {
            return List.of();
        }
        Map<String, String> sourceByChunkId = new LinkedHashMap<>();
        Map<String, Chunk> byId = new HashMap<>(versionChunks.size());
        for (Chunk chunk : versionChunks) {
            byId.put(chunk.getChunkId(), chunk);
            String source = sourceByHash.get(chunk.getChunkTextHash());
            if (source != null) {
                sourceByChunkId.put(chunk.getChunkId(), source);
            }
        }
        // A parent carries one annotation for the whole cascade, so its children are not matched by hash
        // and have to follow their parent here, exactly as they would on a manual disable.
        for (Chunk chunk : versionChunks) {
            String parentSource = chunk.getParentId() == null
                    ? null : sourceByChunkId.get(chunk.getParentId());
            if (parentSource != null) {
                sourceByChunkId.putIfAbsent(chunk.getChunkId(), parentSource);
            }
        }
        List<String> disabled = new ArrayList<>(sourceByChunkId.size());
        for (Map.Entry<String, String> target : sourceByChunkId.entrySet()) {
            Chunk chunk = byId.get(target.getKey());
            if (chunk == null || DISABLED == orEnabled(chunk)) {
                continue;
            }
            chunk.setEnabled(DISABLED);
            chunkMapper.updateById(chunk);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(AnnotationPayloadKeys.ENABLED, false);
            payload.put(AnnotationPayloadKeys.EXCERPT, annotationRecorder.excerpt(chunk.getContent()));
            payload.put(AnnotationPayloadKeys.INHERITED_FROM_VERSION_ID, target.getValue());
            annotationRecorder.recordInherited(chunk, payload);
            disabled.add(chunk.getChunkId());
        }
        if (CollectionUtils.isNotEmpty(disabled)) {
            chunkIndexWriter.syncEnabled(document.getKbId(), disabled, false);
        }
        log.info("disable annotations inherited, docId={}, versionId={}, disabled={}",
                document.getDocId(), version.getVersionId(), disabled.size());
        return disabled;
    }

    /**
     * Annotations of other versions that the target version neither inherited nor repeated.
     *
     * @param docId           document business id
     * @param targetVersionId version the review is performed against
     * @return review items, newest first
     */
    public List<PendingAnnotation> pendingReview(String docId, String targetVersionId) {
        List<Annotation> annotations = annotationMapper.selectList(new LambdaQueryWrapper<Annotation>()
                .eq(Annotation::getDocId, docId));
        if (CollectionUtils.isEmpty(annotations)) {
            return List.of();
        }
        Set<String> coveredByTarget = new LinkedHashSet<>();
        for (Annotation annotation : annotations) {
            if (targetVersionId != null && targetVersionId.equals(annotation.getDocumentVersionId())) {
                coveredByTarget.add(counterpartKey(annotation));
            }
        }
        Map<String, String> versionNumbers = versionNumbersOf(docId);
        List<Annotation> pending = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (targetVersionId != null && targetVersionId.equals(annotation.getDocumentVersionId())) {
                continue;
            }
            if (annotation.getInheritStatus() != InheritStatus.NOT_INHERITED) {
                continue;
            }
            if (coveredByTarget.contains(counterpartKey(annotation))) {
                continue;
            }
            pending.add(annotation);
        }
        pending.sort(Comparator.comparing(Annotation::getId, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        List<PendingAnnotation> views = new ArrayList<>(pending.size());
        for (Annotation annotation : pending) {
            views.add(toView(annotation, versionNumbers.get(annotation.getDocumentVersionId())));
        }
        return views;
    }

    /**
     * Number of review items a version switch would leave open.
     *
     * @param docId           document business id
     * @param targetVersionId version about to be activated
     * @return count of pending review items
     */
    public int staleCount(String docId, String targetVersionId) {
        return pendingReview(docId, targetVersionId).size();
    }

    /**
     * Effective disable state per normalised chunk text, taken from the annotations of other versions.
     *
     * <p>The trail holds every toggle, so a chunk that was disabled and later enabled again appears
     * twice; replaying them in insertion order leaves the last decision standing, which is the only one
     * a new version should inherit.
     *
     * @param docId            document business id
     * @param excludeVersionId version whose own annotations are not a source
     * @return source version id per text digest that has to be disabled
     */
    private Map<String, String> disableSourceByHash(String docId, String excludeVersionId) {
        List<Annotation> toggles = annotationMapper.selectList(new LambdaQueryWrapper<Annotation>()
                .eq(Annotation::getDocId, docId)
                .eq(Annotation::getAnnotationType, AnnotationType.TOGGLE)
                .ne(Annotation::getDocumentVersionId, excludeVersionId)
                .orderByAsc(Annotation::getId));
        Map<String, String> sourceByHash = new LinkedHashMap<>();
        for (Annotation toggle : toggles) {
            if (toggle.getChunkTextHash() == null) {
                continue;
            }
            if (enabledOf(toggle)) {
                sourceByHash.remove(toggle.getChunkTextHash());
            } else {
                sourceByHash.put(toggle.getChunkTextHash(), toggle.getDocumentVersionId());
            }
        }
        return sourceByHash;
    }

    /**
     * Reads the retrieval switch a toggle annotation recorded.
     *
     * <p>A payload that does not say is read as enabled: inheriting a disable is the destructive
     * direction, and guessing it from a malformed row would remove content nobody excluded.
     *
     * @param annotation toggle annotation
     * @return {@code true} when the annotation enabled the chunk
     */
    private boolean enabledOf(Annotation annotation) {
        Object value = payloadOf(annotation).get(AnnotationPayloadKeys.ENABLED);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> payloadOf(Annotation annotation) {
        if (annotation.getPayload() == null || annotation.getPayload().isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtil.parse(annotation.getPayload(),
                new TypeReference<Map<String, Object>>() {
                });
        return parsed == null ? Map.of() : parsed;
    }

    private String counterpartKey(Annotation annotation) {
        return annotation.getAnnotationType().name() + "|" + annotation.getChunkTextHash();
    }

    private Map<String, String> versionNumbersOf(String docId) {
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, docId));
        Map<String, String> numbers = new HashMap<>(versions.size());
        for (DocumentVersion version : versions) {
            numbers.put(version.getVersionId(), version.getVersion());
        }
        return numbers;
    }

    private PendingAnnotation toView(Annotation annotation, String versionNumber) {
        Map<String, Object> payload = payloadOf(annotation);
        Object excerpt = payload.getOrDefault(AnnotationPayloadKeys.EXCERPT,
                payload.get(AnnotationPayloadKeys.BEFORE_EXCERPT));
        return new PendingAnnotation(
                annotation.getAnnotationId(),
                annotation.getKbId(),
                annotation.getDocId(),
                annotation.getDocumentVersionId(),
                versionNumber,
                annotation.getChunkId(),
                annotation.getAnnotationType().name(),
                payload,
                excerpt == null ? null : String.valueOf(excerpt),
                annotation.getChunkTextHash(),
                annotation.getInheritStatus() == null ? null : annotation.getInheritStatus().name(),
                annotation.getOperator(),
                annotation.getCreatedAt() == null ? null : annotation.getCreatedAt().toString());
    }

    private int orEnabled(Chunk chunk) {
        return chunk.getEnabled() == null ? ENABLED : chunk.getEnabled();
    }

    /**
     * One review item: the whole stored annotation plus the two values the console cannot derive.
     *
     * <p>The record mirrors the annotation columns one for one so the console can bind a single
     * annotation type against both this endpoint and any future one that returns stored rows. Only
     * {@code version} and {@code excerpt} are derived — the first joins the version number that the row
     * only references by id, the second lifts the text out of the operation payload, whose key differs
     * per operation kind and which the console would otherwise have to know about.
     *
     * <p>{@code inheritStatus} is the stored value rather than a recomputed one. Today the review filter
     * only ever admits NOT_INHERITED rows, so the field is constant in this response; it is reported
     * anyway because it is the column the console renders and because widening the filter must not
     * silently change what the field means.
     *
     * @param annotationId      annotation business id
     * @param kbId              owning knowledge base
     * @param docId             owning document, stable across versions
     * @param documentVersionId version the operation was performed against
     * @param version           version number of that version, {@code null} when it is gone
     * @param chunkId           chunk the operation named
     * @param annotationType    operation kind
     * @param payload           full operation detail
     * @param excerpt           original text excerpt the operator has to recognise
     * @param chunkTextHash     normalised text digest that drives the cross version inheritance
     * @param inheritStatus     cross version state of the annotation
     * @param operator          console account that performed the operation
     * @param createdAt         ISO timestamp of the operation
     */
    public record PendingAnnotation(String annotationId, String kbId, String docId,
                                    String documentVersionId, String version, String chunkId,
                                    String annotationType, Map<String, Object> payload, String excerpt,
                                    String chunkTextHash, String inheritStatus, String operator,
                                    String createdAt) {
    }
}
