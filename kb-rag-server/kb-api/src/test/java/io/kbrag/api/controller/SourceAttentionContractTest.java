package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.extsource.ExtSourceService;
import io.kbrag.app.websource.WebSourceService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 新筛选参数必须绑定到服务端分页，旧客户端不传参时保持原行为。 */
class SourceAttentionContractTest {
    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void shouldBindAttentionOnlyAndPreserveDefaultForBothSourceTypes() throws Exception {
        UserContextHolder.set(new UserPrincipal("reader", "tenant", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of("kb:read"), true, Set.of()));
        var web = mock(WebSourceService.class);
        var ext = mock(ExtSourceService.class);
        var guard = mock(KbResourceGuard.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebSourceController(web), new ExtSourceController(ext, guard))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(web.list("kb_one", 1, 20, true)).thenReturn(new Page<>());
        when(web.list("kb_one", 1, 20, false)).thenReturn(new Page<>());
        when(ext.list("kb_one", 1, 20, true)).thenReturn(new Page<>());
        when(ext.list("kb_one", 1, 20, false)).thenReturn(new Page<>());
        for (String type : Set.of("web", "ext")) {
            String path = "/api/v1/kb/kb_one/" + type + "-sources";
            mvc.perform(get(path).param("attention_only", "true")).andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isOk());
            mvc.perform(get(path).param("attention_only", "arbitrary")).andExpect(status().isBadRequest());
        }
        verify(web).list("kb_one", 1, 20, true); verify(web).list("kb_one", 1, 20, false);
        verify(ext).list("kb_one", 1, 20, true); verify(ext).list("kb_one", 1, 20, false);
        verify(guard, times(2)).requireKb("kb_one");
    }
}
