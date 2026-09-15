package io.kbrag.app.workspace;

import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.enums.KnowledgeTodoKind;
import io.kbrag.domain.mapper.KnowledgeTodoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页聚合归工作台服务编排，继续复用知识库服务的租户与根范围规则。 */
@Service
@RequiredArgsConstructor
public class KnowledgeTodoService {
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeTodoMapper todos;

    /** 同一读取快照内先查授权根、再聚合，避免无权记录影响数量和排序。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Todo> list() {
        AccessGuard.requirePermission(PermissionCodes.KB_READ);
        var principal = AccessGuard.currentUser();
        Map<String, String> names = new LinkedHashMap<>();
        knowledgeBases.list().forEach(kb -> names.put(kb.getKbId(), kb.getName()));
        if (names.isEmpty()) return List.of();
        return todos.counts(List.copyOf(names.keySet())).stream()
                .map(count -> new Todo(count.getKbId(), names.get(count.getKbId()), count.getKind(), count.getTotal(),
                        principal.hasPermission(count.getKind() == KnowledgeTodoKind.PENDING_REVIEW
                                ? PermissionCodes.DOC_REVIEW : PermissionCodes.DOC_WRITE)))
                .sorted(Comparator.comparingLong(Todo::total).reversed().thenComparing(Todo::kbId)
                        .thenComparing(Todo::kind))
                .toList();
    }

    /** 动作能力用于按钮文案，进入详情后的写操作仍须重新授权。 */
    public record Todo(String kbId, String kbName, KnowledgeTodoKind kind, long total, boolean canProcess) { }
}
