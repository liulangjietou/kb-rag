package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.quality.EmployeeFeedbackAccess;
import io.kbrag.app.quality.EmployeeFeedbackService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 HTTP 拦截器、分页输入和显式投影契约。 */
class EmployeeFeedbackHttpTest {
    private static final String ROOT = "/api/v1/kb/kb/employee-feedback";
    private final EmployeeFeedbackService service = mock(EmployeeFeedbackService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeFeedbackController(service))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void unauthorizedOrEmployeeOnlyAccountCannotBrowseOtherEmployeesFeedback() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        bind("app:use");
        mvc.perform(get(ROOT + "/run")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void restrictedListHasNeitherQuestionNorNoteAndUsesBoundedPageSize() throws Exception {
        bind("feedback:manage", "app:read");
        Page<EmployeeFeedbackService.View> page = new Page<>(1, 50, 1);
        page.setRecords(List.of(new EmployeeFeedbackService.View("opaque", null)));
        when(service.list("kb", 1, 50)).thenReturn(page);
        mvc.perform(get(ROOT + "?page=0&size=2000")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items[0].content_restricted").value(true))
                .andExpect(jsonPath("$.data.items[0].question").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].feedback_note").doesNotExist());
    }

    @Test
    void originalAnswerNeverSerializesEmployeeIdentitySessionOrModelConfiguration() throws Exception {
        bind("feedback:manage", "app:read");
        var run = new EmployeeConversationRun(); run.setRunId("run"); run.setQuestion("原问题"); run.setAnswer("原回答");
        run.setFeedbackVerdict(FeedbackVerdict.BAD); run.setUserId("private-user"); run.setConversationId("private-conversation");
        run.setTargetJson("private-config");
        when(service.detail("kb", "run")).thenReturn(new EmployeeFeedbackAccess.Source(run, "v1", List.of(), "av1"));
        mvc.perform(get(ROOT + "/run")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.answer").value("原回答"))
                .andExpect(jsonPath("$.data.user_id").doesNotExist()).andExpect(jsonPath("$.data.conversation_id").doesNotExist())
                .andExpect(jsonPath("$.data.target_json").doesNotExist()).andExpect(jsonPath("$.data.targetJson").doesNotExist());
    }

    private void bind(String... permissions) {
        UserContextHolder.set(new UserPrincipal("operator", "tenant", "operator", "维护人", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permissions), true, Set.of(), true, Set.of()));
    }
}
