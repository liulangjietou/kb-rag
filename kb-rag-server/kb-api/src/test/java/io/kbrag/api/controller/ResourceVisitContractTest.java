package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.workspace.ResourceVisitService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.ResourceVisitKind;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MVC 绑定验证身份权限、最小输入和 no-store 响应。 */
class ResourceVisitContractTest {
    private static final String PATH = "/api/v1/me/resource-visits";
    private final ResourceVisitService service = mock(ResourceVisitService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceVisitController(service))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRequireManagementReadAccessRatherThanEmployeeUseAlone() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        bind("app:use");
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        mvc.perform(delete(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnOnlyCurrentResourceMetadataAndActualVisitTime() throws Exception {
        bind("kb:read");
        when(service.recent()).thenReturn(List.of(new ResourceVisitService.RecentVisit(
                ResourceVisitKind.KB, "kb_a", "知识库", LocalDateTime.of(2026, 9, 11, 12, 0))));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].resource_type").value("KB"))
                .andExpect(jsonPath("$.data[0].resource_id").value("kb_a"))
                .andExpect(jsonPath("$.data[0].visited_at").value("2026-09-11T12:00"))
                .andExpect(jsonPath("$.data[0].user_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].url").doesNotExist());
    }

    @Test
    void shouldBindTheResourceAndClearOnlyThroughTheCurrentSession() throws Exception {
        bind("app:read");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resource_type\":\"APP\",\"resource_id\":\"app_a\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).remember(ResourceVisitKind.APP, "app_a");
        mvc.perform(delete(PATH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).clear();
    }

    @Test
    void shouldRejectMalformedOrUnsupportedResourceInputsBeforeTheService() throws Exception {
        bind("kb:read");
        for (String body : List.of("{}", "{\"resource_type\":\"URL\",\"resource_id\":\"https://example.test\"}",
                "{\"resource_type\":\"KB\",\"resource_id\":\" \"}",
                "{\"resource_type\":\"KB\",\"resource_id\":\"" + "x".repeat(65) + "\"}")) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user_a", "tenant_a", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of()));
    }
}
