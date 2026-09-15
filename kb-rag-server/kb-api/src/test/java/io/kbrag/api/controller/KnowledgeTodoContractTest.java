package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.workspace.KnowledgeTodoService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.KnowledgeTodoKind;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 层验证员工入口不读取管理摘要，返回值不可缓存且不附带来源配置。 */
class KnowledgeTodoContractTest {
    private static final String PATH = "/api/v1/me/knowledge-todos";
    private final KnowledgeTodoService service = mock(KnowledgeTodoService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeTodoController(service))
            .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void shouldRejectAnonymousAndEmployeeOnlyBeforeAggregation() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        bind("app:use");
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnMinimalReadonlySummaryWithoutCaching() throws Exception {
        bind("kb:read");
        when(service.list()).thenReturn(List.of(new KnowledgeTodoService.Todo(
                "kb_a", "知识库", KnowledgeTodoKind.EXT_SOURCE_ATTENTION, 2, false)));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].kb_id").value("kb_a"))
                .andExpect(jsonPath("$.data[0].kind").value("EXT_SOURCE_ATTENTION"))
                .andExpect(jsonPath("$.data[0].total").value(2))
                .andExpect(jsonPath("$.data[0].can_process").value(false))
                .andExpect(jsonPath("$.data[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].secret_key").doesNotExist());
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user_a", "tenant_a", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), false, Set.of("kb_a")));
    }
}
