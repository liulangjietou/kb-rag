package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.PrincipalResolver;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 员工目录与会话共用的当前授权边界；不从应用管理权推导使用权。 */
@Service
@RequiredArgsConstructor
public class EmployeeWorkspaceAccess {
    private final PrincipalResolver principals;
    private final TenantMapper tenants;
    private final AppMapper apps;
    private final AppVersionMapper versions;
    private final AppVersionService versionService;
    private final KnowledgeBaseMapper knowledgeBases;

    /** 每次内容入口读取当前身份，不信任会话中缓存的角色和范围。 */
    public UserPrincipal current() {
        return refresh(AccessGuard.currentUser());
    }

    /** 后台执行使用原请求的稳定身份，用户跨租户迁移后禁止继续运行。 */
    public UserPrincipal refresh(UserPrincipal expected) {
        UserPrincipal principal = principals.resolveFresh(expected.username());
        if (!expected.userId().equals(principal.userId()) || !expected.tenantId().equals(principal.tenantId())) {
            throw BizException.unauthorized("session no longer valid");
        }
        if (!principal.hasPermission(PermissionCodes.APP_USE)) {
            throw BizException.forbidden("permission required: " + PermissionCodes.APP_USE);
        }
        Tenant tenant = tenants.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, principal.tenantId()).last("limit 1"));
        if (tenant == null || !tenant.enabled()) throw BizException.unauthorized("tenant unavailable");
        return principal;
    }

    /** 历史读取也要检查应用仍属于该租户并在使用范围内，不要求此时还有发布版本。 */
    public App requireApp(UserPrincipal principal, String appId) {
        if (!principal.canAccessApp(appId)) throw BizException.notFound("application not found");
        List<App> visible = apps.listInTenant(principal.tenantId(), List.of(appId));
        if (visible.isEmpty()) throw BizException.notFound("application not found");
        return visible.get(0);
    }

    /** 将通过检查的身份与应用变为账本所需的显式归属。 */
    public EmployeeConversationScope scope(UserPrincipal principal, String appId) {
        requireApp(principal, appId);
        return new EmployeeConversationScope(principal.tenantId(), principal.userId(), appId);
    }

    /** 新一轮只选择服务端的正式版本，并验证其全部知识库均可读。 */
    public EmployeeRunTarget releasedTarget(UserPrincipal principal, String appId) {
        requireApp(principal, appId);
        AppVersion version = versions.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppId, appId).eq(AppVersion::getStatus, AppVersionStatus.RELEASED));
        if (version == null) throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED, "应用尚未发布");
        requireKnowledgeBases(principal, versionService.parseConfig(version).kbIds());
        return EmployeeRunTarget.capture(version);
    }

    /** 执行已接受的问题前重验当前授权，但继续使用已捕获的配置和读取语义。 */
    public void requireTarget(UserPrincipal principal, EmployeeRunTarget target) {
        requireApp(principal, target.appId());
        AppConfigSnapshot config = JsonUtil.parse(target.config(), AppConfigSnapshot.class);
        requireKnowledgeBases(principal, config.kbIds());
    }

    /** 批量校验知识库根的租户、存活状态和当前范围，目录与运行复用同一判定。 */
    public Set<String> accessibleKnowledgeBases(UserPrincipal principal, Set<String> ids) {
        if (ids.isEmpty()) return Set.of();
        return knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .select(KnowledgeBase::getKbId).eq(KnowledgeBase::getTenantId, principal.tenantId())
                        .in(KnowledgeBase::getKbId, ids)).stream()
                .map(KnowledgeBase::getKbId).filter(principal::canAccessKb).collect(Collectors.toSet());
    }

    private void requireKnowledgeBases(UserPrincipal principal, List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED, "应用版本未配置知识库");
        }
        if (!accessibleKnowledgeBases(principal, Set.copyOf(ids)).containsAll(ids)) {
            throw BizException.forbidden("application knowledge base is unavailable");
        }
    }
}
