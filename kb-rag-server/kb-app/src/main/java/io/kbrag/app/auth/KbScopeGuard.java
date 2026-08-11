package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.DocAcl;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.enums.DocVisibility;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data scope checks for resources addressed by their own id rather than by a knowledge base id.
 *
 * <p>Half of the console URLs name a document, a data set or a feedback row directly, with no {@code kbId}
 * anywhere in the request. Without this, a caller scoped to one knowledge base could read or edit a document
 * of another one simply by knowing its id - the function level permission would pass and nothing else would
 * look. So each of those ids is resolved to the knowledge base that owns it and handed to
 * {@link AccessGuard#requireKbAccess(String)}.
 *
 * <p>A bean rather than static methods because the resolution needs the mappers. Every method starts with the
 * {@link AccessGuard#unrestrictedKbScope()} short circuit, so the common case - an administrator, or the API
 * key path with no console caller - costs no query at all.
 *
 * <p>A missing row is reported as not found rather than forbidden. The row genuinely is not there, and
 * answering "forbidden" would send an operator looking for a permission problem that does not exist.
 *
 * <p><b>What is checked here is the data scope, never the tenant.</b> The two are different questions
 * and only the second one is an isolation boundary: {@code kb_scope_all} - a tenant's SUPER_ADMIN, a
 * KB_ADMIN with no scope configured - short circuits every method below, and even without it
 * {@link AccessGuard#requireKbAccess(String)} only asks whether the caller's roles name that base.
 * Neither compares a tenant. A resource of a subordinate table therefore needs its own resolution
 * through the fenced root table before it gets here; {@code WebSourceGuard} is what that looks like,
 * and {@code requireWebSourceAccess} used to stand here pretending to be it.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class KbScopeGuard {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final AnnotationMapper annotationMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final ExtSourceMapper extSourceMapper;
    private final RetrievalFeedbackMapper retrievalFeedbackMapper;
    private final DocAclMapper docAclMapper;

    /**
     * Asserts the knowledge base owning a document is inside the caller's scope.
     *
     * @param docId document business id
     */
    public void requireDocumentAccess(String docId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .select(Document::getKbId)
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        requireOwner(document == null ? null : document.getKbId(), "document", docId);
    }

    /**
     * Asserts the caller may read the <em>content</em> of a document: the scope check of
     * {@link #requireDocumentAccess(String)} plus the document level ACL (M16 contract section 5).
     *
     * <p>Deliberately <b>not</b> short circuited by {@link AccessGuard#unrestrictedKbScope()}:
     * {@code kb_scope_all} answers "which bases", never "which rows inside one". The single bypass is
     * the {@code doc:review} permission - whoever can change a clearance cannot be hidden from the
     * content it protects. A caller with no console principal, the API key path, holds no roles and is
     * therefore refused every restricted document.
     *
     * @param docId document business id
     */
    public void requireDocumentContentAccess(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .select(Document::getKbId, Document::getVisibility)
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("document not found: " + docId);
        }
        if (!AccessGuard.unrestrictedKbScope()) {
            AccessGuard.requireKbAccess(document.getKbId());
        }
        if (document.getVisibility() != DocVisibility.RESTRICTED) {
            return;
        }
        UserPrincipal principal = AccessGuard.currentUserOrNull();
        if (principal != null && principal.hasPermission(PermissionCodes.DOC_REVIEW)) {
            return;
        }
        if (principal == null || CollectionUtils.isEmpty(principal.roleIds())) {
            throw BizException.forbidden("document is restricted: " + docId);
        }
        Set<String> granted = docAclMapper.selectList(new LambdaQueryWrapper<DocAcl>()
                        .select(DocAcl::getRoleId)
                        .eq(DocAcl::getDocumentId, docId))
                .stream()
                .map(DocAcl::getRoleId)
                .collect(Collectors.toSet());
        if (principal.roleIds().stream().noneMatch(granted::contains)) {
            throw BizException.forbidden("document is restricted: " + docId);
        }
    }

    /**
     * Asserts the knowledge base owning a chunk is inside the caller's scope.
     *
     * @param chunkId chunk business id
     */
    public void requireChunkAccess(String chunkId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .select(Chunk::getKbId)
                .eq(Chunk::getChunkId, chunkId)
                .last("limit 1"));
        requireOwner(chunk == null ? null : chunk.getKbId(), "chunk", chunkId);
    }

    /**
     * Asserts the knowledge base owning an annotation is inside the caller's scope.
     *
     * @param annotationId annotation business id
     */
    public void requireAnnotationAccess(String annotationId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        Annotation annotation = annotationMapper.selectOne(new LambdaQueryWrapper<Annotation>()
                .select(Annotation::getKbId)
                .eq(Annotation::getAnnotationId, annotationId)
                .last("limit 1"));
        requireOwner(annotation == null ? null : annotation.getKbId(), "annotation", annotationId);
    }

    /**
     * Asserts the knowledge base owning an evaluation data set is inside the caller's scope.
     *
     * @param datasetId data set business id
     */
    public void requireDatasetAccess(String datasetId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        EvalDataset dataset = evalDatasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                .select(EvalDataset::getKbId)
                .eq(EvalDataset::getDatasetId, datasetId)
                .last("limit 1"));
        requireOwner(dataset == null ? null : dataset.getKbId(), "evaluation data set", datasetId);
    }

    /**
     * Asserts the knowledge base owning the data set of an evaluation case is inside the caller's scope.
     *
     * <p>Two hops, since a case names only its data set. Both are cheap point lookups and the whole thing is
     * skipped for an unrestricted caller.
     *
     * @param caseId case business id
     */
    public void requireCaseAccess(String caseId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        EvalCase evalCase = evalCaseMapper.selectOne(new LambdaQueryWrapper<EvalCase>()
                .select(EvalCase::getDatasetId)
                .eq(EvalCase::getCaseId, caseId)
                .last("limit 1"));
        if (evalCase == null) {
            throw BizException.notFound("evaluation case not found: " + caseId);
        }
        requireDatasetAccess(evalCase.getDatasetId());
    }

    /**
     * Asserts the knowledge base owning an evaluation run is inside the caller's scope.
     *
     * @param runId run business id
     */
    public void requireRunAccess(String runId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        EvalRun run = evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>()
                .select(EvalRun::getKbId)
                .eq(EvalRun::getRunId, runId)
                .last("limit 1"));
        requireOwner(run == null ? null : run.getKbId(), "evaluation run", runId);
    }

    /**
     * Asserts the knowledge base owning an external data source is inside the caller's scope.
     *
     * @param sourceId source business id
     */
    public void requireExtSourceAccess(String sourceId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        ExtSource source = extSourceMapper.selectOne(new LambdaQueryWrapper<ExtSource>()
                .select(ExtSource::getKbId)
                .eq(ExtSource::getSourceId, sourceId)
                .last("limit 1"));
        requireOwner(source == null ? null : source.getKbId(), "external data source", sourceId);
    }

    /**
     * Asserts the knowledge base owning a retrieval feedback row is inside the caller's scope.
     *
     * @param feedbackId feedback business id
     */
    public void requireFeedbackAccess(String feedbackId) {
        if (AccessGuard.unrestrictedKbScope()) {
            return;
        }
        RetrievalFeedback feedback = retrievalFeedbackMapper.selectOne(
                new LambdaQueryWrapper<RetrievalFeedback>()
                        .select(RetrievalFeedback::getKbId)
                        .eq(RetrievalFeedback::getFeedbackId, feedbackId)
                        .last("limit 1"));
        requireOwner(feedback == null ? null : feedback.getKbId(), "retrieval feedback", feedbackId);
    }

    /**
     * Applies the scope check to a resolved owner.
     *
     * @param kbId     owning knowledge base, {@code null} when the row does not exist
     * @param resource resource kind, for the rejection message
     * @param id       resource business id, for the rejection message
     */
    private void requireOwner(String kbId, String resource, String id) {
        if (kbId == null) {
            throw BizException.notFound(resource + " not found: " + id);
        }
        AccessGuard.requireKbAccess(kbId);
    }
}
