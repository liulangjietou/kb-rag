package io.kbrag.app.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.DocAcl;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.enums.DocVisibility;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Document level visibility: the single place {@code t_kb_doc_acl} is written, and the retrieval side
 * trim that keeps a restricted document out of answers its caller may not see (M16 contract section 5).
 *
 * <p><b>Deliberately uncached.</b> The M15 knowledge base scope lives in a per user principal cache
 * because it changes rarely and is read on every call. A document ACL is the opposite trade: it is read
 * only on the paths that expose content, the judgement is one indexed query plus an in memory set
 * intersection, and a stale grant here is a disclosure, not a slow page. Five milliseconds are cheaper
 * than an expired clearance.
 *
 * <p><b>Rebinding is delete then insert</b>, the same discipline as the three role association tables
 * of M15: the table carries a logical delete column, so an update in place would resurrect historical
 * rows and a unique key would refuse legitimate regrants.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAclService {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocAclMapper docAclMapper;
    private final RoleMapper roleMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * Current visibility of a document together with the granted roles.
     *
     * @param kbId  knowledge base named by the URL, checked against the owning base
     * @param docId document business id
     * @return visibility and the granted role ids, the latter empty unless RESTRICTED
     */
    public VisibilityView visibility(String kbId, String docId) {
        Document document = requireOwned(kbId, docId);
        if (visibilityOf(document) != DocVisibility.RESTRICTED) {
            return new VisibilityView(DocVisibility.INHERIT, List.of());
        }
        List<String> roleIds = docAclMapper.selectList(new LambdaQueryWrapper<DocAcl>()
                        .eq(DocAcl::getDocumentId, docId))
                .stream()
                .map(DocAcl::getRoleId)
                .distinct()
                .toList();
        return new VisibilityView(DocVisibility.RESTRICTED, roleIds);
    }

    /**
     * Replaces the visibility of a document and its complete grant set.
     *
     * @param kbId       knowledge base named by the URL, checked against the owning base
     * @param docId      document business id
     * @param visibility new visibility
     * @param roleIds    granted roles, required non empty for RESTRICTED and empty for INHERIT
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVisibility(String kbId, String docId, DocVisibility visibility,
                                 List<String> roleIds) {
        Document document = requireOwned(kbId, docId);
        Set<String> distinct = visibility == DocVisibility.RESTRICTED
                ? validatedRoleIds(roleIds) : Set.of();
        document.setVisibility(visibility);
        documentMapper.updateById(document);
        docAclMapper.deleteByDocumentId(docId);
        for (String roleId : distinct) {
            DocAcl grant = new DocAcl();
            grant.setDocumentId(docId);
            grant.setRoleId(roleId);
            docAclMapper.insert(grant);
        }
        log.info("document visibility updated, docId={}, visibility={}, roles={}",
                docId, visibility, distinct.size());
    }

    /**
     * Drops from a visible version set the versions of restricted documents the current caller may not
     * read, the retrieval half of the contract.
     *
     * <p>Applied to the live set and to a frozen snapshot set alike: a release freezes which versions
     * answer, not who may see them, and the open API path in particular must never surface a document
     * that was restricted after the release. A call with no console caller - the open API - carries no
     * roles, so every restricted document is dropped for it.
     *
     * <p>One indexed query on the common path (no restricted document in the base returns the set
     * untouched), two more only when something actually has to be dropped.
     *
     * @param kbId       knowledge base the set belongs to
     * @param versionIds candidate document version ids
     * @return the set minus the versions of unreadable restricted documents
     */
    public List<String> trimRestricted(String kbId, List<String> versionIds) {
        if (CollectionUtils.isEmpty(versionIds)) {
            return versionIds;
        }
        List<Document> restricted = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .select(Document::getDocId)
                .eq(Document::getKbId, kbId)
                .eq(Document::getVisibility, DocVisibility.RESTRICTED));
        if (CollectionUtils.isEmpty(restricted)) {
            return versionIds;
        }
        Map<String, Set<String>> grantedByDocId = grantedRoleIds(restricted);
        Set<String> blockedDocIds = restricted.stream()
                .map(Document::getDocId)
                .filter(docId -> !readableByCurrentCaller(docId, grantedByDocId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (blockedDocIds.isEmpty()) {
            return versionIds;
        }
        Set<String> blockedVersionIds = documentVersionMapper.selectList(
                        new LambdaQueryWrapper<DocumentVersion>()
                                .select(DocumentVersion::getVersionId)
                                .in(DocumentVersion::getDocId, blockedDocIds))
                .stream()
                .map(DocumentVersion::getVersionId)
                .collect(Collectors.toSet());
        return versionIds.stream().filter(versionId -> !blockedVersionIds.contains(versionId)).toList();
    }

    /**
     * Removes the grants of a deleted document, called from the hard delete chain.
     *
     * @param docId document business id
     */
    public void detachDocument(String docId) {
        int removed = docAclMapper.deleteByDocumentId(docId);
        if (removed > 0) {
            log.info("document grants removed with the document, docId={}, rows={}", docId, removed);
        }
    }

    /**
     * Removes the grants held through a deleted role.
     *
     * @param roleId role business id
     */
    public void detachRole(String roleId) {
        int removed = docAclMapper.deleteByRoleId(roleId);
        if (removed > 0) {
            log.info("document grants removed with the role, roleId={}, rows={}", roleId, removed);
        }
    }

    /**
     * Whether the current caller may read the content of one restricted document.
     *
     * <p>The one bypass is the {@code doc:review} permission - whoever can change a clearance cannot be
     * hidden from the content - and it is checked by the caller of this method where the permission
     * codes live. Here the answer is purely "does any role held intersect the grant rows".
     *
     * @param docId          restricted document business id
     * @param grantedByDocId grant rows of every restricted document of the base, grouped lookup
     * @return {@code true} when a role held is granted
     */
    private boolean readableByCurrentCaller(String docId,
                                            Map<String, Set<String>> grantedByDocId) {
        UserPrincipal principal = AccessGuard.currentUserOrNull();
        if (principal == null) {
            // The open API: an end user has no roles, and a released application must not become a
            // bypass around a clearance.
            return false;
        }
        Set<String> granted = grantedByDocId.get(docId);
        if (CollectionUtils.isEmpty(granted) || CollectionUtils.isEmpty(principal.roleIds())) {
            return false;
        }
        return principal.roleIds().stream().anyMatch(granted::contains);
    }

    /**
     * Grant rows of every restricted document of one query, fetched once and grouped by document.
     *
     * @param restricted restricted documents of the base
     * @return document id to granted role ids
     */
    private Map<String, Set<String>> grantedRoleIds(List<Document> restricted) {
        List<String> docIds = restricted.stream().map(Document::getDocId).toList();
        return docAclMapper.selectList(new LambdaQueryWrapper<DocAcl>()
                        .in(DocAcl::getDocumentId, docIds))
                .stream()
                .collect(Collectors.groupingBy(DocAcl::getDocumentId,
                        Collectors.mapping(DocAcl::getRoleId, Collectors.toSet())));
    }

    private Set<String> validatedRoleIds(List<String> roleIds) {
        Set<String> distinct = new LinkedHashSet<>(roleIds);
        long known = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .in(Role::getRoleId, distinct));
        if (known != distinct.size()) {
            throw BizException.invalidParam("提交的角色中存在不属于当前租户或不存在的角色");
        }
        return distinct;
    }

    private Document requireOwned(String kbId, String docId) {
        // 「文档挂在这个库下」和「这个库是你的」是两个问题。只问前者的话，跨租户的调用方
        // 把别家的 kbId 与该库下的 docId 一起传进来就完全对得上，密级与授权角色照读照改。
        knowledgeBaseService.require(kbId);
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null || !document.getKbId().equals(kbId)) {
            throw BizException.notFound("document not found: " + docId);
        }
        return document;
    }

    /** Rows written before the M16 migration carry a null column and are INHERIT by contract. */
    private DocVisibility visibilityOf(Document document) {
        return document.getVisibility() == null ? DocVisibility.INHERIT : document.getVisibility();
    }

    /**
     * Visibility of one document as the console reads it.
     *
     * @param visibility current visibility
     * @param roleIds    granted role ids, empty unless RESTRICTED
     */
    public record VisibilityView(DocVisibility visibility, List<String> roleIds) {
    }
}
