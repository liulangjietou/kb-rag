package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.workspace.EmployeeAppCatalogService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 应用使用权限与管理、调试权限独立；员工响应中禁止出现模型和检索配置。 */
class EmployeeAppCatalogContractTest {
    private final EmployeeAppCatalogService catalog = mock(EmployeeAppCatalogService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeAppCatalogController(catalog))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app:read", "app:write", "app:release", "search:debug", "role:manage"})
    void shouldRejectManagementPermissionsWithoutAppUse(String permission) throws Exception {
        bind(permission);
        mvc.perform(get("/api/v1/workspace/apps")).andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }

    @Test
    void shouldExposeOnlyEmployeeEntryMetadata() throws Exception {
        bind("app:use");
        App app = new App();
        app.setAppId("app_a");
        app.setName("员工助手");
        app.setTenantId("private_tenant");
        AppVersion version = new AppVersion();
        version.setAppVersionId("version_1");
        version.setVersion("v1");
        version.setConfig("private prompt and model");
        when(catalog.list()).thenReturn(List.of(new EmployeeAppCatalogService.ReleasedApplication(app, version)));
        mvc.perform(get("/api/v1/workspace/apps")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].app_id").value("app_a"))
                .andExpect(jsonPath("$.data[0].released_version_id").value("version_1"))
                .andExpect(jsonPath("$.data[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].config").doesNotExist());
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("u1", "t1", "employee", "Employee", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of()));
    }
}
