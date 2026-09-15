package io.kbrag.api.controller;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.api.sse.EmployeeRunSubscriptions;
import io.kbrag.app.workspace.EmployeeConversationHistory.RunView;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.EmployeeAnswerFeedback;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际 MVC 绑定、权限、错误码和返回字段共同约束反馈接口。 */
class EmployeeAnswerFeedbackContractTest {
    private static final String PATH = "/api/v1/workspace/apps/app/conversations/conv/runs/run/feedback";
    private static final String BODY = "{\"verdict\":\"BAD\",\"note\":\"  缺少日期依据  \",\"expected_revision\":5}";
    private final EmployeeConversationService service = mock(EmployeeConversationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeConversationController(service, mock(EmployeeRunSubscriptions.class)))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void shouldRequireAppUseAndReturnNormalizedFeedbackWithNoStore() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        bind("app:read");
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        bind("app:use");
        LocalDateTime now = LocalDateTime.of(2026, 9, 11, 10, 0);
        when(service.feedback("app", "conv", "run", FeedbackVerdict.BAD, "缺少日期依据", 5)).thenReturn(
                new RunView("run", "conv", 1, "问题", "回答", List.of(), ConversationRunStatus.SUCCEEDED,
                        ConversationRunStage.FINISHED, "av", "v1", true, 6, 1, false, null, null,
                        false, now, now, new EmployeeAnswerFeedback(FeedbackVerdict.BAD, "缺少日期依据", now)));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.revision").value(6))
                .andExpect(jsonPath("$.data.feedback.verdict").value("BAD"))
                .andExpect(jsonPath("$.data.feedback.note").value("缺少日期依据"))
                .andExpect(jsonPath("$.data.feedback.updated_at").exists())
                .andExpect(jsonPath("$.data.user_id").doesNotExist());
        verify(service).feedback("app", "conv", "run", FeedbackVerdict.BAD, "缺少日期依据", 5);
    }

    @Test
    void shouldRejectMalformedFeedbackBeforeCallingTheService() throws Exception {
        bind("app:use");
        for (String body : List.of("{}", "{\"verdict\":\"BAD\"}",
                "{\"verdict\":\"UNKNOWN\",\"expected_revision\":5}",
                "{\"verdict\":\"BAD\",\"expected_revision\":-1}",
                "{\"verdict\":\"BAD\",\"expected_revision\":5,\"note\":\"" + "长".repeat(513) + "\"}")) {
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldMapAStaleFeedbackRevisionToConflict() throws Exception {
        bind("app:use");
        when(service.feedback("app", "conv", "run", FeedbackVerdict.BAD, "缺少日期依据", 5))
                .thenThrow(new BizException(ErrorCode.FEEDBACK_VERSION_CONFLICT, "请重新读取反馈"));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEEDBACK_VERSION_CONFLICT"));
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of(), true, Set.of()));
    }
}
