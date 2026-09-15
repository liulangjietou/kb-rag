package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.PrincipalResolver;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
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
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 员工目录的撤权、版本、跨库和最小查询边界。 */
class EmployeeAppCatalogServiceTest {
    private final PrincipalResolver principals = mock(PrincipalResolver.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final AppMapper apps = mock(AppMapper.class);
    private final AppVersionMapper versions = mock(AppVersionMapper.class);
    private final AppVersionService versionService = mock(AppVersionService.class);
    private final KnowledgeBaseMapper kbs = mock(KnowledgeBaseMapper.class);
    private final EmployeeAppCatalogService service = new EmployeeAppCatalogService(
            principals, tenants, apps, versions, versionService, kbs);

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Tenant.class, AppVersion.class, KnowledgeBase.class);
        UserPrincipal user = principal(Set.of("app:use"), true, Set.of(), true, Set.of());
        UserContextHolder.set(user);
        when(principals.resolveFresh("employee")).thenReturn(user);
        Tenant tenant = new Tenant();
        tenant.setTenantId("tenant_a");
        tenant.setStatus(TenantStatus.ENABLED);
        when(tenants.selectOne(any())).thenReturn(tenant);
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRefuseRevokedFunctionPermissionEvenWhenSessionSnapshotStillGrantsIt() {
        when(principals.resolveFresh("employee")).thenReturn(
                principal(Set.of("app:read", "search:debug"), true, Set.of(), true, Set.of()));
        assertThrows(BizException.class, service::list);
        verifyNoInteractions(apps, versions, kbs);
    }

    @Test
    void shouldReturnNothingAfterAppScopeIsRevokedWithoutQueryingApplications() {
        when(principals.resolveFresh("employee")).thenReturn(
                principal(Set.of("app:use"), false, Set.of(), true, Set.of()));
        assertTrue(service.list().isEmpty());
        verifyNoInteractions(apps, versions, kbs);
    }

    @Test
    void shouldRefuseMissingTenantAndUnauthenticatedCalls() {
        when(tenants.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, service::list);
        UserContextHolder.clear();
        assertThrows(BizException.class, service::list);
        verifyNoInteractions(apps, versions, kbs);
    }

    @Test
    void shouldFenceApplicationsByExplicitTenantAndScopeBeforeReadingVersions() {
        when(principals.resolveFresh("employee")).thenReturn(
                principal(Set.of("app:use"), false, Set.of("app_a"), true, Set.of()));
        when(apps.listInTenant("tenant_a", List.of("app_a"))).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
        verify(apps).listInTenant("tenant_a", List.of("app_a"));
        verifyNoInteractions(versions, kbs);
    }

    @Test
    void shouldOnlyExposeReleasedApplicationsWithAllKnowledgeBasesCurrentlyAccessible() {
        when(principals.resolveFresh("employee")).thenReturn(
                principal(Set.of("app:use"), true, Set.of(), false, Set.of("kb_allowed", "kb_deleted")));
        var allowed = version("app_allowed", "kb_allowed");
        var outsideScope = version("app_denied", "kb_allowed", "kb_other");
        var deletedOrForeign = version("app_deleted", "kb_deleted");
        var unconfigured = version("app_unconfigured");
        when(apps.listInTenant("tenant_a", null)).thenReturn(List.of(app("app_allowed"), app("app_denied"),
                app("app_deleted"), app("app_unconfigured"), app("app_draft")));
        when(versions.selectList(any())).thenReturn(List.of(allowed, outsideScope, deletedOrForeign, unconfigured));
        when(kbs.selectList(any())).thenReturn(List.of(kb("kb_allowed"), kb("kb_other")));

        assertEquals(List.of("app_allowed"), service.list().stream().map(item -> item.app().getAppId()).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AppVersion>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(versions).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("app_id IN"));
        assertTrue(sql.contains("status ="));
        assertTrue(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppVersion>) query.getValue())
                .getParamNameValuePairs().containsValue(AppVersionStatus.RELEASED));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KnowledgeBase>> kbQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(kbs).selectList(kbQuery.capture());
        assertTrue(kbQuery.getValue().getSqlSegment().contains("tenant_id ="));
        assertTrue(kbQuery.getValue().getSqlSegment().contains("kb_id IN"));
    }

    @Test
    void shouldNotTreatAllKnowledgeBaseScopeAsPermissionToReadMissingRoots() {
        when(apps.listInTenant("tenant_a", null)).thenReturn(List.of(app("app_a")));
        var released = version("app_a", "kb_foreign");
        when(versions.selectList(any())).thenReturn(List.of(released));
        when(kbs.selectList(any())).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
    }

    private UserPrincipal principal(Set<String> permissions, boolean allApps, Set<String> appIds,
                                    boolean allKbs, Set<String> kbIds) {
        return new UserPrincipal("usr_employee", "tenant_a", "employee", "Employee", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, allKbs, kbIds, allApps, appIds);
    }

    private App app(String id) {
        App app = new App();
        app.setAppId(id);
        app.setTenantId("tenant_a");
        return app;
    }

    private AppVersion version(String appId, String... kbIds) {
        AppVersion version = new AppVersion();
        version.setAppId(appId);
        version.setAppVersionId("version_" + appId);
        version.setStatus(AppVersionStatus.RELEASED);
        AppConfigSnapshot config = new AppConfigSnapshot();
        config.setKbRefs(Arrays.stream(kbIds).map(KbRef::of).toList());
        when(versionService.parseConfig(version)).thenReturn(config);
        return version;
    }

    private KnowledgeBase kb(String id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setKbId(id);
        kb.setTenantId("tenant_a");
        return kb;
    }
}
