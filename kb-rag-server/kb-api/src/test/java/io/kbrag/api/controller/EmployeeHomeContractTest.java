package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.workspace.EmployeeAppCatalogService;
import io.kbrag.app.workspace.EmployeeHomeService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 首页与正式员工入口使用相同功能权限，返回字段显式限制为目录和私有摘要。 */
class EmployeeHomeContractTest {
    private static final String PATH = "/api/v1/workspace/overview";
    private final EmployeeHomeService service = mock(EmployeeHomeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeHomeController(service))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRequireLoginAndAppUseIndependentlyOfManagementRights() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        bind("app:read");
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnNoStoreEmptyOverviewForAnEmployeeWithoutAvailableApplications() throws Exception {
        bind("app:use");
        when(service.overview()).thenReturn(new EmployeeHomeService.Overview(List.of(), List.of()));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.applications").isEmpty())
                .andExpect(jsonPath("$.data.recent_conversations").isEmpty());
    }

    @Test
    void shouldExposeMetadataWithoutConfigurationOrInternalOwnerFields() throws Exception {
        bind("app:use");
        App app = new App();
        app.setAppId("app_a");
        app.setName("员工助手");
        app.setTenantId("private_tenant");
        AppVersion version = new AppVersion();
        version.setAppVersionId("version_a");
        version.setVersion("v1");
        version.setConfig("private prompt and model");
        EmployeeConversation conversation = new EmployeeConversation();
        conversation.setConversationId("conv_a");
        conversation.setAppId("app_a");
        conversation.setTitle("我的会话");
        conversation.setTenantId("private_tenant");
        conversation.setUserId("private_owner");
        conversation.setLastActivityAt(LocalDateTime.of(2026, 9, 11, 10, 0));
        when(service.overview()).thenReturn(new EmployeeHomeService.Overview(
                List.of(new EmployeeAppCatalogService.ReleasedApplication(app, version)), List.of(conversation)));
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applications[0].released_version_id").value("version_a"))
                .andExpect(jsonPath("$.data.applications[0].config").doesNotExist())
                .andExpect(jsonPath("$.data.applications[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data.recent_conversations[0].conversation_id").value("conv_a"))
                .andExpect(jsonPath("$.data.recent_conversations[0].app_name").value("员工助手"))
                .andExpect(jsonPath("$.data.recent_conversations[0].user_id").doesNotExist())
                .andExpect(jsonPath("$.data.recent_conversations[0].tenant_id").doesNotExist());
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user_a", "tenant_a", "employee", "员工", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of()));
    }
}
