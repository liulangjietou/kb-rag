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
import io.kbrag.domain.entity.KnowledgeBase;
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
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single point at which a console call resolves a resource addressed by its own id to the
 * knowledge base that owns it, and decides whether the caller may act on it.
 *
 * <p>Half of the console URLs name a document, a chunk, a data set or a feedback row directly, with
 * no {@code kbId} anywhere in the request. Every one of those tables is a subordinate of
 * {@code t_kb_knowledge_base} - none carries a {@code tenant_id}, none is in
 * {@code KbTenantLineHandler.FENCED_TABLES}, and correctly so: a second tenant column would be a
 * second fact that can disagree with the first. The consequence is that a statement over one of
 * those tables crosses tenants freely, and the fence is not what stops it. <b>Resolving the root
 * first is what stops it</b>, and that is the whole job of this class.
 *
 * <p><b>Why this class was renamed from {@code KbScopeGuard}.</b> The old name described what it
 * actually did - answer "is this base inside the data scope configured on the caller's roles" - and
 * that question is not an isolation boundary. It compared no tenant, and every method opened with
 * {@code if (AccessGuard.unrestrictedKbScope()) return;}, which short circuits for a tenant's
 * SUPER_ADMIN and for any KB_ADMIN with no scope configured. Since {@code V16__rbac.sql} seeds all
 * five built in roles with {@code kb_scope_all = 1} and {@code TenantService} copies them into every
 * new tenant, that branch is taken in essentially every real deployment: the guard cost nothing and
 * decided nothing. A guard that only covers the data scope is worse than no guard at all, because
 * review reads the call and concludes the path is protected. The short circuit is gone from every
 * method below for the same reason - it skipped not just the scope decision but the root lookup that
 * carries the tenant clause.
 *
 * <p><b>Tenant first, data scope second.</b> A resource whose base belongs to another tenant answers
 * 404 - outside the caller's tenant the row does not exist as far as they can observe. Only inside
 * the own tenant does the data scope apply, and that one answers 403: a base of the own tenant that
 * a role does not name is a permission problem an operator can act on. Running the two in the other
 * order leaks "this id exists elsewhere" through the difference between the two codes.
 *
 * <p><b>What one check costs.</b> Two point lookups on unique keys: one to find the owning base id
 * in the subordinate table, one to read that base through the fence. The first is unavoidable - the
 * id only exists in the subordinate table - and neither writes anything. The decision happens on the
 * second, before any statement that reads content, writes or spends a credential.
 *
 * <p>A thread with no console principal - the open API, the scheduled passes - is fenced by neither
 * hop, which is the pre-existing semantics kept intact: those callers locate rows by exact business
 * id and are already bounded by the application version or the key they came in with, and an empty
 * data scope there would mean "sees nothing" rather than "sees everything".
 *
 * <p>A missing row is reported as not found rather than forbidden. The row genuinely is not there,
 * and answering "forbidden" would send an operator looking for a permission problem that does not
 * exist.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class KbResourceGuard {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
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
     * Asserts the caller may act on a knowledge base named directly by the request.
     *
     * <p>The one entry shape that costs a single statement: there is no subordinate row to locate
     * first, so the fenced read of the root is the whole check. Callers that already hold the base
     * row have nothing to gain from this - they went through {@code KnowledgeBaseService#require},
     * which reads the same fenced table.
     *
     * @param kbId knowledge base business id
     * @throws BizException not found when the base does not exist or belongs to another tenant,
     *                      forbidden when it sits outside the caller's data scope
     */
    public void requireKb(String kbId) {
        if (findFencedBase(kbId) == null) {
            throw BizException.notFound("knowledge base not found: " + kbId);
        }
        requireScope(kbId);
    }

    /**
     * Asserts the caller may act on a document.
     *
     * @param docId document business id
     */
    public void requireDocumentAccess(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .select(Document::getKbId)
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        requireOwner(document == null ? null : document.getKbId(), "document", docId);
    }

    /**
     * Asserts the caller may read the <em>content</em> of a document: everything
     * {@link #requireDocumentAccess(String)} asks plus the document level ACL (M16 contract
     * section 5).
     *
     * <p>The ACL is deliberately not covered by {@code kb_scope_all}: that flag answers "which
     * bases", never "which rows inside one". The single bypass is the {@code doc:review} permission
     * - whoever can change a clearance cannot be hidden from the content it protects. A caller with
     * no console principal, the API key path, holds no roles and is therefore refused every
     * restricted document.
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
        requireOwner(document.getKbId(), "document", docId);
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
     * Asserts the caller may act on a chunk.
     *
     * @param chunkId chunk business id
     */
    public void requireChunkAccess(String chunkId) {
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .select(Chunk::getKbId)
                .eq(Chunk::getChunkId, chunkId)
                .last("limit 1"));
        requireOwner(chunk == null ? null : chunk.getKbId(), "chunk", chunkId);
    }

    /**
     * Asserts the caller may act on an annotation.
     *
     * @param annotationId annotation business id
     */
    public void requireAnnotationAccess(String annotationId) {
        Annotation annotation = annotationMapper.selectOne(new LambdaQueryWrapper<Annotation>()
                .select(Annotation::getKbId)
                .eq(Annotation::getAnnotationId, annotationId)
                .last("limit 1"));
        requireOwner(annotation == null ? null : annotation.getKbId(), "annotation", annotationId);
    }

    /**
     * Asserts the caller may act on an evaluation data set.
     *
     * <p>The only resource here that needs no second hop: {@code t_kb_eval_dataset} is a root
     * aggregate of its own, it carries a {@code tenant_id} and it is in the fenced set, so another
     * tenant's data set does not come back from the statement below at all. What is left to decide
     * is the data scope of the base it belongs to.
     *
     * @param datasetId data set business id
     */
    public void requireDatasetAccess(String datasetId) {
        EvalDataset dataset = evalDatasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                .select(EvalDataset::getKbId)
                .eq(EvalDataset::getDatasetId, datasetId)
                .last("limit 1"));
        if (dataset == null) {
            throw BizException.notFound("evaluation data set not found: " + datasetId);
        }
        requireScope(dataset.getKbId());
    }

    /**
     * Asserts the caller may act on an evaluation case.
     *
     * <p>Two hops, since a case names only its data set - and the data set is the fenced root, so
     * that is where a foreign tenant is filtered away.
     *
     * @param caseId case business id
     */
    public void requireCaseAccess(String caseId) {
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
     * Asserts the caller may act on an evaluation run.
     *
     * <p>Resolved through the data set rather than through {@code EvalRun.kb_id}, which the row also
     * carries: the data set is the fenced root of this domain, so routing the run through it is the
     * same path {@link #requireCaseAccess(String)} takes and there is one less way for the two to
     * drift apart.
     *
     * @param runId run business id
     */
    public void requireRunAccess(String runId) {
        EvalRun run = evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>()
                .select(EvalRun::getDatasetId)
                .eq(EvalRun::getRunId, runId)
                .last("limit 1"));
        if (run == null) {
            throw BizException.notFound("evaluation run not found: " + runId);
        }
        requireDatasetAccess(run.getDatasetId());
    }

    /**
     * Asserts the caller may act on an external data source.
     *
     * @param sourceId source business id
     */
    public void requireExtSourceAccess(String sourceId) {
        ExtSource source = extSourceMapper.selectOne(new LambdaQueryWrapper<ExtSource>()
                .select(ExtSource::getKbId)
                .eq(ExtSource::getSourceId, sourceId)
                .last("limit 1"));
        requireOwner(source == null ? null : source.getKbId(), "external data source", sourceId);
    }

    /**
     * Asserts the caller may act on a retrieval feedback row.
     *
     * @param feedbackId feedback business id
     */
    public void requireFeedbackAccess(String feedbackId) {
        RetrievalFeedback feedback = retrievalFeedbackMapper.selectOne(
                new LambdaQueryWrapper<RetrievalFeedback>()
                        .select(RetrievalFeedback::getKbId)
                        .eq(RetrievalFeedback::getFeedbackId, feedbackId)
                        .last("limit 1"));
        requireOwner(feedback == null ? null : feedback.getKbId(), "retrieval feedback", feedbackId);
    }

    /**
     * Runs both decisions on a resolved owner: the tenant through the fenced root table, then the
     * data scope.
     *
     * <p>A base the fence reads as missing is reported as <em>the subordinate resource</em> being
     * absent, not the base. The caller asked about the resource, and the id of a base in another
     * tenant is not theirs to learn.
     *
     * @param kbId     owning knowledge base, {@code null} when the subordinate row does not exist
     * @param resource resource kind, for the rejection message
     * @param id       resource business id, for the rejection message
     */
    private void requireOwner(String kbId, String resource, String id) {
        if (kbId == null || findFencedBase(kbId) == null) {
            throw BizException.notFound(resource + " not found: " + id);
        }
        requireScope(kbId);
    }

    /**
     * Reads a knowledge base through the tenant fence.
     *
     * <p>The tenant clause is added by the MyBatis interceptor, not written here: on a console
     * thread the statement is trimmed to the caller's tenant and a foreign base simply does not come
     * back.
     *
     * @param kbId knowledge base business id
     * @return the base, or {@code null} when it does not exist or belongs to another tenant
     */
    private KnowledgeBase findFencedBase(String kbId) {
        return knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .select(KnowledgeBase::getKbId)
                .eq(KnowledgeBase::getKbId, kbId)
                .last("limit 1"));
    }

    /**
     * Applies the data scope of the caller to an already tenant-resolved base.
     *
     * @param kbId knowledge base business id
     */
    private void requireScope(String kbId) {
        if (!AccessGuard.unrestrictedKbScope()) {
            AccessGuard.requireKbAccess(kbId);
        }
    }
}
