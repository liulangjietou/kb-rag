package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.extsource.ExtSourceService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 失败重试必须先通过写权限和来源范围，读取失败筛选与旧参数保持兼容。 */
class ExtSourceRetryContractTest {
    private final ExtSourceService service = mock(ExtSourceService.class);
    private final KbResourceGuard guard = mock(KbResourceGuard.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExtSourceController(service, guard))
            .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void shouldAuthorizeBeforeAcceptingRetryAndReturnOnlyAcceptance() throws Exception {
        bind("doc:write");
        mvc.perform(post("/api/v1/ext-sources/source_one/retry-failed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        var order = inOrder(guard, service);
        order.verify(guard).requireExtSourceAccess("source_one");
        order.verify(service).ensureExists("source_one");
        order.verify(service).retryFailedAsync("source_one");
    }

    @Test
    void shouldRefuseReadOnlyAndOutOfScopeWithoutStartingWork() throws Exception {
        bind("kb:read");
        mvc.perform(post("/api/v1/ext-sources/source_one/retry-failed")).andExpect(status().isForbidden());
        verifyNoInteractions(service, guard);
        bind("doc:write");
        doThrow(BizException.notFound("source not found")).when(guard).requireExtSourceAccess("hidden");
        mvc.perform(post("/api/v1/ext-sources/hidden/retry-failed")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void shouldApplyFailedFilterBeforePaginationAndKeepOldDefault() throws Exception {
        bind("kb:read");
        when(service.listItems("source_one", 1, 20, true)).thenReturn(new Page<>());
        when(service.listItems("source_one", 1, 20, false)).thenReturn(new Page<>());
        mvc.perform(get("/api/v1/ext-sources/source_one/items").param("failed_only", "true")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ext-sources/source_one/items")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ext-sources/source_one/items").param("failed_only", "invalid")).andExpect(status().isBadRequest());
        verify(service).listItems("source_one", 1, 20, true);
        verify(service).listItems("source_one", 1, 20, false);
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of()));
    }
}
