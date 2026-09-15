package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.auth.RoleAppScopeService;
import io.kbrag.app.auth.RoleService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 HTTP 绑定、校验和权限拦截，避免可选字段被自动转成全量清空。 */
class RoleAppScopeContractTest {
    private final RoleService roles = mock(RoleService.class);
    private final RoleAppScopeService scopes = mock(RoleAppScopeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RoleController(roles, scopes))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        bind(Set.of("role:manage"));
    }

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void shouldResolveOptionsFromAuthorizedRoleAndExposeOnlyNameAndId() throws Exception {
        when(roles.get("role_b")).thenReturn(role());
        App app = new App();
        app.setAppId("app_b");
        app.setTenantId("tenant_b");
        app.setName("知识助手");
        app.setDescription("非选择器字段");
        when(scopes.optionsInTenant("tenant_b")).thenReturn(List.of(app));
        mvc.perform(get("/api/v1/roles/app-options").param("role_id", "role_b").param("tenant_id", "tenant_evil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].app_id").value("app_b"))
                .andExpect(jsonPath("$.data[0].name").value("知识助手"))
                .andExpect(jsonPath("$.data[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].description").doesNotExist());
        verify(roles).get("role_b");
        verify(scopes).optionsInTenant("tenant_b");
    }

    @Test
    void shouldUseCurrentTenantForNewRoleOptions() throws Exception {
        mvc.perform(get("/api/v1/roles/app-options")).andExpect(status().isOk());
        verify(scopes).optionsInTenant("tenant_a");
        verifyNoInteractions(roles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"app:read", "app:use", "search:debug", "user:manage", "doc:review"})
    void shouldRequireRoleManagementForScopeOptions(String permission) throws Exception {
        bind(Set.of(permission));
        mvc.perform(get("/api/v1/roles/app-options")).andExpect(status().isForbidden());
        verifyNoInteractions(roles, scopes);
    }

    @Test
    void shouldKeepOmittedScopeNullableForLegacyUpdates() throws Exception {
        when(roles.get("role_b")).thenReturn(role());
        when(scopes.scopesOf(List.of("role_b"))).thenReturn(Map.of("role_b", List.of("app_b")));
        mvc.perform(put("/api/v1/roles/role_b").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名\",\"kb_scope_all\":true,\"kb_ids\":[],\"permission_codes\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.app_ids[0]").value("app_b"));
        verify(roles).update("role_b", "改名", null, true, List.of(), List.of(), null, null);
    }

    @Test
    void shouldPassExplicitScopeOnCreateAndBatchItOnList() throws Exception {
        when(roles.create(any(), any(), isNull(), eq(true), any(), any(), eq(false), any())).thenReturn(role());
        when(roles.list()).thenReturn(List.of(role()));
        when(scopes.scopesOf(List.of("role_b"))).thenReturn(Map.of("role_b", List.of("app_b")));
        mvc.perform(post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"EMPLOYEE","name":"员工","kb_scope_all":true,"kb_ids":[],
                 "permission_codes":["app:use"],"app_scope_all":false,"app_ids":["app_b"]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.app_scope_all").value(false));
        verify(roles).create("EMPLOYEE", "员工", null, true, List.of(), List.of("app:use"), false, List.of("app_b"));
        mvc.perform(get("/api/v1/roles")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].app_ids[0]").value("app_b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"app_ids\":[\"app_b\"]", "\"app_scope_all\":false,\"app_ids\":[\" \" ]",
            "\"app_scope_all\":false,\"app_ids\":[null]"})
    void shouldRejectIncompleteOrInvalidScopeBeforeWriting(String fields) throws Exception {
        mvc.perform(put("/api/v1/roles/role_b").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"员工\",\"kb_scope_all\":true," + fields + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(roles, scopes);
    }

    private Role role() {
        Role role = new Role();
        role.setRoleId("role_b");
        role.setTenantId("tenant_b");
        role.setName("员工");
        role.setAppScopeAll(false);
        return role;
    }

    private void bind(Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("operator", "tenant_a", "operator", "Operator", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of()));
    }
}
