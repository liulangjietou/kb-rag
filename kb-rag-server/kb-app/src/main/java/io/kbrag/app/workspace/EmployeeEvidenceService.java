package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 员工证据的捕获与当前权限重验，供生成、历史展示和模型上下文共同使用。 */
@Service
@RequiredArgsConstructor
public class EmployeeEvidenceService {
    private static final String CHUNK_SEQ = "chunk_seq";
    private static final int MAX_LOCATOR_NUMBER = 1_000_000;
    private final EmployeeWorkspaceAccess access;
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentAclService acl;

    /** 捕获实际检索结果；引用正文沿用已裁剪的内容，不重新读取未裁剪的原始 chunk。 */
    public List<EmployeeCitation> capture(UserPrincipal principal, List<RetrievalNodeView> nodes) {
        if (nodes.isEmpty()) return List.of();
        Set<String> docIds = nodes.stream().map(RetrievalNodeView::getDocId).collect(Collectors.toSet());
        Map<String, Document> documentMap = documentMap(docIds);
        Map<String, DocumentVersion> versionMap = versionMap(nodes.stream()
                .map(RetrievalNodeView::getDocumentVersionId).collect(Collectors.toSet()));
        List<EmployeeCitation> citations = new ArrayList<>(nodes.size());
        for (var node : nodes) {
            Document document = documentMap.get(node.getDocId());
            DocumentVersion version = versionMap.get(node.getDocumentVersionId());
            if (document == null || version == null || !document.getDocId().equals(version.getDocId())) {
                throw unavailable();
            }
            Map<String, Object> metadata = node.getMetadata() == null ? Map.of() : node.getMetadata();
            // DOCX、表格和网页中的 page_no 是逻辑分段，不能伪装成原文的物理页码。
            Integer page = "pdf".equalsIgnoreCase(document.getFileExt())
                    ? integer(metadata.get(ChunkMetadataKeys.PAGE_NO), 1) : null;
            Integer sequence = integer(metadata.get(CHUNK_SEQ), 0);
            Object title = metadata.get(ChunkMetadataKeys.TITLE);
            citations.add(new EmployeeCitation(document.getDocId(), version.getVersionId(), node.getChunkId(),
                    document.getKbId(), document.getFileName(), version.getVersion(), document.getUpdatedAt(),
                    version.getCreatedAt(), title instanceof String text ? text : null, page,
                    sequence == null ? null : sequence + 1, node.getContent(), false));
        }
        if (!canRead(principal, citations, documentMap, versionMap)) throw unavailable();
        return List.copyOf(citations);
    }

    /** 任一依赖已撤权即遮蔽整条回答，不能只隐藏引用却继续显示由它生成的答案。 */
    public boolean canReadAll(UserPrincipal principal, List<EmployeeCitation> citations) {
        if (citations.isEmpty()) return true;
        return canRead(principal, citations,
                documentMap(citations.stream().map(EmployeeCitation::docId).collect(Collectors.toSet())),
                versionMap(citations.stream().map(EmployeeCitation::documentVersionId).collect(Collectors.toSet())));
    }

    private boolean canRead(UserPrincipal principal, List<EmployeeCitation> citations,
                            Map<String, Document> documentMap, Map<String, DocumentVersion> versionMap) {
        Set<String> kbIds = citations.stream().map(EmployeeCitation::kbId).collect(Collectors.toSet());
        if (!access.accessibleKnowledgeBases(principal, kbIds).containsAll(kbIds)) return false;
        LocalDateTime now = LocalDateTime.now();
        for (EmployeeCitation citation : citations) {
            Document document = documentMap.get(citation.docId());
            DocumentVersion version = versionMap.get(citation.documentVersionId());
            if (document == null || version == null || !citation.kbId().equals(document.getKbId())
                    || !citation.docId().equals(version.getDocId()) || !document.availableForRetrievalAt(now)) {
                return false;
            }
        }
        Map<String, Set<String>> byKb = citations.stream().collect(Collectors.groupingBy(EmployeeCitation::kbId,
                Collectors.mapping(EmployeeCitation::documentVersionId, Collectors.toCollection(LinkedHashSet::new))));
        // 复用正式检索的 ACL 规则，并绑定本次从数据库重新解析的身份；不能沿用后台旧线程上下文。
        UserPrincipal previous = UserContextHolder.get();
        UserContextHolder.set(principal);
        try {
            for (var entry : byKb.entrySet()) {
                if (!acl.trimRestricted(entry.getKey(), List.copyOf(entry.getValue())).containsAll(entry.getValue())) {
                    return false;
                }
            }
            return true;
        } finally {
            if (previous == null) UserContextHolder.clear();
            else UserContextHolder.set(previous);
        }
    }

    private Map<String, Document> documentMap(Set<String> ids) {
        return documents.selectList(new LambdaQueryWrapper<Document>().in(Document::getDocId, ids)).stream()
                .collect(Collectors.toMap(Document::getDocId, Function.identity()));
    }

    private Map<String, DocumentVersion> versionMap(Set<String> ids) {
        return versions.selectList(new LambdaQueryWrapper<DocumentVersion>().in(DocumentVersion::getVersionId, ids)).stream()
                .collect(Collectors.toMap(DocumentVersion::getVersionId, Function.identity()));
    }

    private Integer integer(Object raw, int minimum) {
        if (raw == null) return null;
        try {
            int parsed = Integer.parseInt(raw.toString());
            return parsed >= minimum && parsed < MAX_LOCATOR_NUMBER ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BizException unavailable() {
        return BizException.forbidden("引用内容已不可用，请重新提问");
    }
}
