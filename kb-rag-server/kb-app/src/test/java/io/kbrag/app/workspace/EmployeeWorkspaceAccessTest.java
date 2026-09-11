package io.kbrag.app.workspace;

import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.PrincipalResolver;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 会话运行不能利用旧身份、跨租户应用或只授权了部分知识库的配置。 */
class EmployeeWorkspaceAccessTest {
    private final PrincipalResolver principals = mock(PrincipalResolver.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final AppVersionMapper versions = mock(AppVersionMapper.class);
    private final AppVersionService versionService = mock(AppVersionService.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final EmployeeWorkspaceAccess access = new EmployeeWorkspaceAccess(principals, tenants, apps,
            versions, versionService, knowledgeBases);
    private final UserPrincipal employee = principal("user_a", "tenant_a", Set.of("app:use"), Set.of("app_a"));

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Tenant.class, AppVersion.class, KnowledgeBase.class);
        UserContextHolder.set(employee);
        when(principals.resolveFresh("employee")).thenReturn(employee);
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.ENABLED);
        when(tenants.selectOne(any())).thenReturn(tenant);
        App app = new App();
        app.setAppId("app_a");
        app.setTenantId("tenant_a");
        when(apps.listInTenant("tenant_a", List.of("app_a"))).thenReturn(List.of(app));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRejectUserReplacementAndTenantTransfer() {
        when(principals.resolveFresh("employee")).thenReturn(principal("user_b", "tenant_a", Set.of("app:use"), Set.of("app_a")));
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BizException.class, access::current).getErrorCode());
        when(principals.resolveFresh("employee")).thenReturn(principal("user_a", "tenant_b", Set.of("app:use"), Set.of("app_a")));
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BizException.class, () -> access.refresh(employee)).getErrorCode());
        verifyNoInteractions(apps, versions, knowledgeBases);
    }

    @Test
    void shouldRequireCurrentUsePermissionAndEnabledTenantOnWorkerRefresh() {
        when(principals.resolveFresh("employee")).thenReturn(principal("user_a", "tenant_a", Set.of("app:read"), Set.of("app_a")));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class, () -> access.refresh(employee)).getErrorCode());
        when(principals.resolveFresh("employee")).thenReturn(employee);
        when(tenants.selectOne(any())).thenReturn(null);
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BizException.class, () -> access.refresh(employee)).getErrorCode());
    }

    @Test
    void shouldAllowOwnedHistoryWithoutRequiringAnExistingRelease() {
        var scope = access.scope(employee, "app_a");
        assertEquals("tenant_a", scope.tenantId());
        assertEquals("user_a", scope.userId());
        assertEquals("app_a", scope.appId());
        verifyNoInteractions(versions, versionService, knowledgeBases);
    }

    @Test
    void shouldHideUnscopedAndDeletedApplicationsBeforeReadingConfiguration() {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> access.requireApp(employee, "app_b")).getErrorCode());
        when(apps.listInTenant("tenant_a", List.of("app_a"))).thenReturn(List.of());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> access.scope(employee, "app_a")).getErrorCode());
        verifyNoInteractions(versions, versionService, knowledgeBases);
    }

    @Test
    void shouldRejectReleaseWithOneMissingOrUnscopedKnowledgeBase() {
        var config = config(List.of("kb_a", "kb_b"));
        var version = version(config);
        when(versions.selectOne(any())).thenReturn(version);
        when(versionService.parseConfig(version)).thenReturn(config);
        when(knowledgeBases.selectList(any())).thenReturn(List.of(kb("kb_a")));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> access.releasedTarget(employee, "app_a")).getErrorCode());
        when(knowledgeBases.selectList(any())).thenReturn(List.of(kb("kb_a"), kb("kb_b")));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> access.releasedTarget(employee, "app_a")).getErrorCode());
    }

    @Test
    void shouldRejectMissingReleaseAndUnconfiguredRelease() {
        assertEquals(ErrorCode.VERSION_NOT_PUBLISHED, assertThrows(BizException.class,
                () -> access.releasedTarget(employee, "app_a")).getErrorCode());
        var config = config(List.of());
        var version = version(config);
        when(versions.selectOne(any())).thenReturn(version);
        when(versionService.parseConfig(version)).thenReturn(config);
        assertEquals(ErrorCode.VERSION_NOT_PUBLISHED, assertThrows(BizException.class,
                () -> access.releasedTarget(employee, "app_a")).getErrorCode());
    }

    @Test
    void shouldRevalidateCapturedTargetWithoutResolvingANewerVersion() {
        var target = new EmployeeRunTarget("app_a", "av_old", "V1.0", JsonUtil.toJson(config(List.of("kb_a"))), null, null, false);
        when(knowledgeBases.selectList(any())).thenReturn(List.of(kb("kb_a")));
        access.requireTarget(employee, target);
        verifyNoInteractions(versions, versionService);
        when(knowledgeBases.selectList(any())).thenReturn(List.of());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> access.requireTarget(employee, target)).getErrorCode());
    }

    private UserPrincipal principal(String userId, String tenantId, Set<String> permissions, Set<String> appIds) {
        return new UserPrincipal(userId, tenantId, "employee", "Employee", UserSource.LOCAL, Set.of(), Set.of(),
                permissions, false, Set.of("kb_a"), false, appIds);
    }

    private AppConfigSnapshot config(List<String> ids) {
        AppConfigSnapshot config = new AppConfigSnapshot();
        config.setKbRefs(ids.stream().map(KbRef::of).toList());
        return config;
    }

    private AppVersion version(AppConfigSnapshot config) {
        var version = new AppVersion();
        version.setAppId("app_a");
        version.setAppVersionId("av_a");
        version.setVersion("V1.0");
        version.setStatus(AppVersionStatus.RELEASED);
        version.setConfig(JsonUtil.toJson(config));
        return version;
    }

    private KnowledgeBase kb(String id) {
        var kb = new KnowledgeBase();
        kb.setKbId(id);
        return kb;
    }
}
