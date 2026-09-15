package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.ResourceVisit;
import io.kbrag.domain.enums.ResourceVisitKind;
import io.kbrag.domain.mapper.ResourceVisitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 访问记录归当前用户所有；资源名称和可见范围每次读取时重新解析。 */
@Service
@RequiredArgsConstructor
public class ResourceVisitService {
    private static final int RECENT_LIMIT = 5;
    private final ResourceVisitMapper visits;
    private final KbResourceGuard guard;
    private final KnowledgeBaseService knowledgeBases;
    private final AppService apps;

    /** 详情已成功呈现后由客户端记录；当前资源授权通过才写入，时间不由客户端提供。 */
    public void remember(ResourceVisitKind kind, String resourceId) {
        var principal = AccessGuard.currentUser();
        switch (kind) {
            case KB -> {
                AccessGuard.requirePermission(PermissionCodes.KB_READ);
                guard.requireKb(resourceId);
            }
            case APP -> {
                AccessGuard.requirePermission(PermissionCodes.APP_READ);
                apps.require(resourceId);
            }
        }
        visits.remember(principal.tenantId(), principal.userId(), kind, resourceId, LocalDateTime.now());
    }

    /** 先使用现有管理资源范围裁剪，再在数据库取最近五项，撤权记录不会挤占结果数量。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecentVisit> recent() {
        var principal = AccessGuard.currentUser();
        Map<ResourceKey, String> names = new LinkedHashMap<>();
        if (principal.hasPermission(PermissionCodes.KB_READ)) {
            knowledgeBases.list().forEach(kb ->
                    names.put(new ResourceKey(ResourceVisitKind.KB, kb.getKbId()), kb.getName()));
        }
        if (principal.hasPermission(PermissionCodes.APP_READ)) {
            // 管理详情遵循 app:read 与租户边界，员工 app:use 的应用范围由独立工作台负责。
            apps.list().forEach(app ->
                    names.put(new ResourceKey(ResourceVisitKind.APP, app.getAppId()), app.getName()));
        }
        if (names.isEmpty()) {
            return List.of();
        }
        List<String> kbIds = names.keySet().stream()
                .filter(key -> key.kind() == ResourceVisitKind.KB).map(ResourceKey::id).toList();
        List<String> appIds = names.keySet().stream()
                .filter(key -> key.kind() == ResourceVisitKind.APP).map(ResourceKey::id).toList();
        var query = new LambdaQueryWrapper<ResourceVisit>()
                .eq(ResourceVisit::getTenantId, principal.tenantId())
                .eq(ResourceVisit::getUserId, principal.userId())
                .and(scope -> {
                    if (!kbIds.isEmpty()) {
                        scope.nested(kb -> kb.eq(ResourceVisit::getResourceType, ResourceVisitKind.KB)
                                .in(ResourceVisit::getResourceId, kbIds));
                    }
                    if (!kbIds.isEmpty() && !appIds.isEmpty()) {
                        scope.or();
                    }
                    if (!appIds.isEmpty()) {
                        scope.nested(app -> app.eq(ResourceVisit::getResourceType, ResourceVisitKind.APP)
                                .in(ResourceVisit::getResourceId, appIds));
                    }
                })
                .orderByDesc(ResourceVisit::getVisitedAt, ResourceVisit::getId)
                .last("LIMIT " + RECENT_LIMIT);
        return visits.selectList(query).stream().map(visit -> new RecentVisit(visit.getResourceType(),
                visit.getResourceId(), names.get(new ResourceKey(visit.getResourceType(), visit.getResourceId())),
                visit.getVisitedAt())).toList();
    }

    /** 清空本人记录；再次打开资源才会生成新的访问时间。 */
    public void clear() {
        var principal = AccessGuard.currentUser();
        visits.clearOwned(principal.tenantId(), principal.userId());
    }

    private record ResourceKey(ResourceVisitKind kind, String id) { }

    /** 对外只需要资源标识、当前名称与真实访问时间，不传所有者或资源配置。 */
    public record RecentVisit(ResourceVisitKind kind, String resourceId, String name, LocalDateTime visitedAt) { }
}
