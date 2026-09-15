package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.model.EvalCaseInput;
import io.kbrag.domain.model.EvalEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

/** 质量问题在当前租户内处理，正文和人工说明继续受当前文档权限约束。 */
@Component
@RequiredArgsConstructor
public class QualityIssueAccess {
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;
    private final ChunkMapper chunks;
    private final EvalCaseMapper cases;
    private final AppMapper applications;
    private final KbResourceGuard resourceGuard;
    private final AppVersionService versions;

    /** 先在 SQL 中确认租户，再判断知识库范围，平台角色也不能绕过当前租户。 */
    public void requireKb(String kbId) {
        var principal = AccessGuard.currentUser();
        KnowledgeBase kb = knowledgeBases.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .select(KnowledgeBase::getKbId)
                .eq(KnowledgeBase::getKbId, kbId).eq(KnowledgeBase::getTenantId, principal.tenantId()));
        if (kb == null) throw BizException.notFound("knowledge base not found");
        AccessGuard.requireKbAccess(kbId);
    }

    /** 原反馈资料和纠正证据都需要重新校验；失去任一权限后不展示处理说明。 */
    public void requireContent(KnowledgeQualityIssue issue) {
        if (issue.getExpectedCaseInput() != null) {
            AccessGuard.requirePermission(PermissionCodes.EVAL_READ);
            AccessGuard.requirePermission(PermissionCodes.APP_READ);
            requireVersion(issue.getKbId(), issue.getAffectedAppVersionId());
        }
        if (issue.getSourceDocId() != null) requireDocument(issue.getKbId(), issue.getSourceDocId());
        if (issue.getProtectedDocIds() != null) {
            for (String docId : JsonUtil.parse(issue.getProtectedDocIds(), new TypeReference<List<String>>() { })) {
                requireDocument(issue.getKbId(), docId);
            }
        }
        if (issue.getExpectedCaseInput() != null) {
            EvalCaseInput input = JsonUtil.parse(issue.getExpectedCaseInput(), EvalCaseInput.class);
            requireEvidence(issue.getKbId(), JsonUtil.parse(input.evidences(), new TypeReference<List<EvalEvidence>>() { }));
        }
    }

    /** 受限条目保留处理状态，列表投影负责隐藏摘要和内容关联。 */
    public boolean canReadContent(KnowledgeQualityIssue issue) {
        try { requireContent(issue); return true; }
        catch (BizException failure) {
            if (failure.getErrorCode() == ErrorCode.FORBIDDEN || failure.getErrorCode() == ErrorCode.NOT_FOUND) return false;
            throw failure;
        }
    }

    /** 新证据必须属于问题所在知识库，并能通过现有文档内容 ACL。 */
    public void requireEvidence(String kbId, List<EvalEvidence> evidence) {
        for (String docId : evidence.stream().map(EvalEvidence::getDocId).distinct().toList()) requireDocument(kbId, docId);
    }

    /** 返回已经过所属知识库、回收站和当前内容权限校验的文档。 */
    public Document requireDocument(String kbId, String docId) {
        Document document = documents.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId).eq(Document::getDocId, docId));
        if (document == null || document.inTrash()) {
            throw BizException.notFound("evidence document not found");
        }
        resourceGuard.requireDocumentContentAccess(docId);
        return document;
    }

    /** 生成答案还可能引用未被选为标准证据的召回资料，查看与采用结果前也必须校验。 */
    public Set<String> requireResult(String kbId, EvalResult result) {
        List<String> chunkIds = result.getRecalledChunkIds() == null ? List.of()
                : JsonUtil.parse(result.getRecalledChunkIds(), new TypeReference<List<String>>() { });
        if (chunkIds.isEmpty()) return Set.of();
        Set<String> uniqueIds = new LinkedHashSet<>(chunkIds);
        List<Chunk> recalled = chunks.selectList(new LambdaQueryWrapper<Chunk>().in(Chunk::getChunkId, uniqueIds));
        if (recalled.size() != uniqueIds.size()) throw BizException.notFound("regression evidence no longer available");
        Set<String> docIds = new LinkedHashSet<>();
        for (Chunk chunk : recalled) docIds.add(chunk.getDocId());
        for (String docId : docIds) requireDocument(kbId, docId);
        return docIds;
    }

    /** 详情使用评测集当前内容，并校验外部编辑可能引入的新证据。 */
    public EvalCase readCase(KnowledgeQualityIssue issue) { return findCase(issue, false); }

    /** 在写事务内锁住关联用例，使修订号检查、纠正或解决操作不会跨过并发编辑。 */
    public EvalCase lockCase(KnowledgeQualityIssue issue) { return findCase(issue, true); }

    private EvalCase findCase(KnowledgeQualityIssue issue, boolean lock) {
        var query = new LambdaQueryWrapper<EvalCase>().eq(EvalCase::getCaseId, issue.getCaseId())
                .eq(EvalCase::getDatasetId, issue.getDatasetId());
        if (lock) query.last("FOR UPDATE");
        EvalCase current = cases.selectOne(query);
        if (current == null) throw BizException.notFound("correction case not found");
        requireEvidence(issue.getKbId(), JsonUtil.parse(current.getEvidences(), new TypeReference<List<EvalEvidence>>() { }));
        return current;
    }

    /** 受影响版本与验证版本必须属于当前可访问应用，且明确包含此知识库。 */
    public AppVersion requireVersion(String kbId, String versionId) {
        AppVersion version = versions.require(versionId);
        var principal = AccessGuard.currentUser();
        App app = applications.selectOne(new LambdaQueryWrapper<App>()
                .eq(App::getAppId, version.getAppId()).eq(App::getTenantId, principal.tenantId()));
        if (app == null) throw BizException.notFound("application version not found");
        if (!principal.canAccessApp(app.getAppId())) throw BizException.forbidden("应用不在当前账号授权范围内");
        if (!versions.parseConfig(version).kbIds().contains(kbId)) {
            throw BizException.invalidParam("该应用版本未引用问题所在知识库");
        }
        return version;
    }
}
