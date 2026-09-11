package io.kbrag.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.api.sse.EmployeeRunSubscriptions;
import io.kbrag.app.workspace.EmployeeConversationHistory.RunView;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MVC 权限、输入边界和字段契约；业务事务另由账本集成测试覆盖。 */
class EmployeeConversationContractTest {
    private static final String ROOT = "/api/v1/workspace/apps/app/conversations";
    private final EmployeeConversationService conversations = mock(EmployeeConversationService.class);
    private final EmployeeRunSubscriptions subscriptions = mock(EmployeeRunSubscriptions.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeConversationController(conversations, subscriptions))
                .addInterceptors(new PermissionInterceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
        bind("app:use");
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void shouldRequireEmployeePermissionForEveryCommandAndRead() throws Exception {
        bind("app:read");
        for (var request : List.of(get(ROOT), post(ROOT).content("{\"title\":\"新会话\"}"), get(ROOT + "/conv"),
                patch(ROOT + "/conv").content("{\"title\":\"改名\"}"), delete(ROOT + "/conv"),
                get(ROOT + "/conv/runs"), post(ROOT + "/conv/runs").content("{\"request_id\":\"request_1\",\"query\":\"问题\"}"),
                get(ROOT + "/conv/runs/run"), post(ROOT + "/conv/runs/run/stop"), get(ROOT + "/conv/runs/run/events"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(conversations, subscriptions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=0", "?page=100001", "?size=0", "?size=51", "?page=abc", "?size=2147483648"})
    void shouldRejectInvalidPaginationAtHttpBoundary(String query) throws Exception {
        mvc.perform(get(ROOT + query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        verifyNoInteractions(conversations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\"  \"}", "{}"})
    void shouldRejectMissingOrBlankTitle(String body) throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(conversations);
    }

    @Test
    void shouldValidateRunInputBeforeAnySubmission() throws Exception {
        for (String body : List.of("{\"request_id\":\"short\",\"query\":\"问题\"}",
                "{\"request_id\":\"request_1\",\"query\":\" \"}",
                "{\"request_id\":\"request_1\",\"query\":\"" + "长".repeat(8001) + "\"}")) {
            mvc.perform(post(ROOT + "/conv/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(ROOT).param("keyword", "长".repeat(121))).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/conv/runs").param("before_turn", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/conv/runs").param("limit", "51")).andExpect(status().isBadRequest());
        verifyNoInteractions(conversations);
    }

    @Test
    void shouldPreserveRequestIdentityAndQuestionButNotAcceptClientHistoryOrTarget() throws Exception {
        when(conversations.submit("app", "conv", "request_1", " 原问题 ")).thenReturn(runView());
        mvc.perform(post(ROOT + "/conv/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"request_1\",\"query\":\" 原问题 \",\"history\":[\"forged\"],\"target\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run_id").value("run"))
                .andExpect(jsonPath("$.data.app_version_id").value("av_original"))
                .andExpect(jsonPath("$.data.references[0].doc_id").value("doc"))
                .andExpect(jsonPath("$.data.references[0].page_no").doesNotExist())
                .andExpect(jsonPath("$.data.target_json").doesNotExist())
                .andExpect(jsonPath("$.data.owner_token").doesNotExist());
        verify(conversations).submit("app", "conv", "request_1", " 原问题 ");
        verifyNoInteractions(subscriptions);
    }

    @Test
    void shouldKeepSummaryMinimalAndUseDatabasePagination() throws Exception {
        var conversation = EmployeeConversation.create("conv", new EmployeeConversationScope("private_tenant", "private_user", "app"),
                "报销", LocalDateTime.now());
        var page = new Page<EmployeeConversation>(2, 10, 11);
        page.setRecords(List.of(conversation));
        when(conversations.list("app", "报销", 2, 10)).thenReturn(page);
        mvc.perform(get(ROOT).param("keyword", "报销").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.items[0].conversation_id").value("conv"))
                .andExpect(jsonPath("$.data.items[0].tenant_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].user_id").doesNotExist());
        when(conversations.create("app", "新标题")).thenReturn(conversation);
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  新标题  \"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(conversations).create("app", "新标题");
    }

    private RunView runView() {
        return new RunView("run", "conv", 1, " 原问题 ", "回答",
                List.of(new EmployeeCitation("doc", "version", "chunk", "kb", "材料", "v1", null, null, null, null, 2, "原文", false)),
                ConversationRunStatus.RUNNING, ConversationRunStage.GENERATING, "av_original", "v1", true,
                3, 1, false, null, null, false, LocalDateTime.now(), null);
    }

    private void bind(String permission) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permission), true, Set.of(), true, Set.of()));
    }
}
