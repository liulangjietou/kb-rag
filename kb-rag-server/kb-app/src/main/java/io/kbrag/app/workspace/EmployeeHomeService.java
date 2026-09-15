package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 员工首页的只读聚合，不读取答案正文、不创建会话或启动模型。 */
@Service
@RequiredArgsConstructor
public class EmployeeHomeService {
    private static final int RECENT_CONVERSATION_LIMIT = 5;
    private final EmployeeWorkspaceAccess access;
    private final EmployeeAppCatalogService catalog;
    private final EmployeeConversationMapper conversations;

    /** 当前可用应用与本人最近会话来自同一授权快照，SQL 限制数量前完成所有范围裁剪。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview() {
        var principal = access.current();
        var applications = catalog.listFor(principal);
        if (applications.isEmpty()) return new Overview(List.of(), List.of());
        var appIds = applications.stream().map(item -> item.app().getAppId()).toList();
        var recent = conversations.selectList(new LambdaQueryWrapper<EmployeeConversation>()
                .eq(EmployeeConversation::getTenantId, principal.tenantId())
                .eq(EmployeeConversation::getUserId, principal.userId())
                .in(EmployeeConversation::getAppId, appIds)
                .orderByDesc(EmployeeConversation::getLastActivityAt, EmployeeConversation::getId)
                .last("LIMIT " + RECENT_CONVERSATION_LIMIT));
        return new Overview(List.copyOf(applications), List.copyOf(recent));
    }

    /** HTTP 层分别映射最小应用元数据与私有会话摘要。 */
    public record Overview(List<EmployeeAppCatalogService.ReleasedApplication> applications,
                           List<EmployeeConversation> recentConversations) { }
}
