package io.kbrag.app.annotation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.util.HashUtil;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.InheritStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes the audit trail of a chunk operation, once per distinct operation.
 *
 * <p><b>Why an idempotency key.</b> The console retries a save on a dropped response and a browser
 * replays a request on a back navigation. Without a key the trail would fill with rows describing the
 * same change, and the review list of a new document version - which counts those rows - would report a
 * number that means nothing. The key is the target, the operation kind and a discriminator of the
 * content the operation produced, so submitting the same change twice updates one row while a genuinely
 * different change gets its own.
 *
 * <p>The key is deliberately not a database unique constraint: annotations are soft deleted with their
 * document, and a unique index would then reject the identical operation after a re-upload.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnotationRecorder {

    /** Separator of the idempotency key parts. */
    private static final String KEY_SEPARATOR = "|";

    /** Longest text excerpt kept in a payload. */
    private static final int EXCERPT_MAX_LENGTH = 500;

    /** Ellipsis appended to a truncated excerpt. */
    private static final String ELLIPSIS = "...";

    private final AnnotationMapper annotationMapper;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Records an operation whose target is the chunk itself.
     *
     * @param target        chunk supplying the owning ids and the text digest
     * @param type          operation kind
     * @param discriminator content digest of what the operation produced
     * @param payload       operation detail
     * @return stored or refreshed annotation
     */
    public Annotation record(Chunk target, AnnotationType type, String discriminator,
                             Map<String, Object> payload) {
        return record(target, target.getChunkId(), type, discriminator, payload,
                InheritStatus.NOT_INHERITED);
    }

    /**
     * Records an operation whose target is not the chunk carrying the ids.
     *
     * <p>A merge is the case this exists for: the row has to name one chunk and the requirement names
     * the first source, while the knowledge base, document, version and text digest have to come from
     * the chunk the merge produced.
     *
     * @param context       chunk supplying the owning ids and the text digest
     * @param chunkId       chunk the row names
     * @param type          operation kind
     * @param discriminator content digest of what the operation produced
     * @param payload       operation detail
     * @return stored or refreshed annotation
     */
    public Annotation record(Chunk context, String chunkId, AnnotationType type, String discriminator,
                             Map<String, Object> payload) {
        return record(context, chunkId, type, discriminator, payload, InheritStatus.NOT_INHERITED);
    }

    /**
     * Records a disable annotation that was carried over from an older document version.
     *
     * @param target  chunk of the new version that was disabled
     * @param payload operation detail, carrying the origin of the inheritance
     * @return stored or refreshed annotation
     */
    public Annotation recordInherited(Chunk target, Map<String, Object> payload) {
        return record(target, target.getChunkId(), AnnotationType.TOGGLE,
                target.getChunkTextHash() + KEY_SEPARATOR + false, payload, InheritStatus.AUTO_INHERITED);
    }

    /**
     * Shortens a chunk text so a payload stays readable in the review list.
     *
     * @param text chunk text, {@code null} tolerated
     * @return excerpt, {@code null} when there was no text
     */
    public String excerpt(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= EXCERPT_MAX_LENGTH
                ? text
                : text.substring(0, EXCERPT_MAX_LENGTH) + ELLIPSIS;
    }

    private Annotation record(Chunk context, String chunkId, AnnotationType type, String discriminator,
                              Map<String, Object> payload, InheritStatus inheritStatus) {
        String key = HashUtil.sha256Hex(String.join(KEY_SEPARATOR, chunkId, type.name(), discriminator));
        Annotation existing = annotationMapper.selectOne(new LambdaQueryWrapper<Annotation>()
                .eq(Annotation::getIdempotencyKey, key)
                .orderByDesc(Annotation::getId)
                .last("limit 1"));
        if (existing != null) {
            existing.setPayload(JsonUtil.toJson(payload));
            existing.setInheritStatus(inheritStatus);
            annotationMapper.updateById(existing);
            log.info("annotation resubmitted, annotationId={}, type={}, chunkId={}",
                    existing.getAnnotationId(), type, chunkId);
            return existing;
        }
        Annotation annotation = new Annotation();
        annotation.setAnnotationId(bizIdGenerator.annotationId());
        annotation.setKbId(context.getKbId());
        annotation.setDocId(context.getDocId());
        annotation.setDocumentVersionId(context.getDocumentVersionId());
        annotation.setChunkId(chunkId);
        annotation.setAnnotationType(type);
        annotation.setPayload(JsonUtil.toJson(payload));
        annotation.setChunkTextHash(context.getChunkTextHash());
        annotation.setInheritStatus(inheritStatus);
        annotation.setIdempotencyKey(key);
        annotation.setOperator(KbConstants.ANNOTATION_OPERATOR_ADMIN);
        annotationMapper.insert(annotation);
        markOlderVersionsRedone(annotation);
        log.info("annotation recorded, annotationId={}, type={}, docId={}, versionId={}, chunkId={}",
                annotation.getAnnotationId(), type, context.getDocId(),
                context.getDocumentVersionId(), chunkId);
        return annotation;
    }

    /**
     * Closes the review item an older version was still holding open.
     *
     * <p>The same operation kind on the same normalised text is, by definition, the operation the review
     * list was asking for. Marking it here rather than deriving it on every read is what keeps the count
     * shown next to the version list and the list itself from ever disagreeing.
     *
     * @param annotation annotation that was just created
     */
    private void markOlderVersionsRedone(Annotation annotation) {
        if (annotation.getChunkTextHash() == null) {
            return;
        }
        int redone = annotationMapper.update(null, new LambdaUpdateWrapper<Annotation>()
                .set(Annotation::getInheritStatus, InheritStatus.REDONE.name())
                .eq(Annotation::getDocId, annotation.getDocId())
                .eq(Annotation::getAnnotationType, annotation.getAnnotationType())
                .eq(Annotation::getChunkTextHash, annotation.getChunkTextHash())
                .eq(Annotation::getInheritStatus, InheritStatus.NOT_INHERITED)
                .ne(Annotation::getDocumentVersionId, annotation.getDocumentVersionId()));
        if (redone > 0) {
            log.info("older annotations marked as redone, docId={}, type={}, count={}",
                    annotation.getDocId(), annotation.getAnnotationType(), redone);
        }
    }
}
