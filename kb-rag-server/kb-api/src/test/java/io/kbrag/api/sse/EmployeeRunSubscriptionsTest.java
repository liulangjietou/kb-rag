package io.kbrag.api.sse;

import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.app.workspace.EmployeeConversationHistory.RunView;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.app.workspace.EmployeeWorkspaceAccess;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式推进订阅时间，覆盖终态、撤权、错误与连接限额的资源释放。 */
class EmployeeRunSubscriptionsTest {
    private final UserPrincipal principal = new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of(), Set.of("app:use"), true, Set.of(), true, Set.of());
    private final EmployeeConversationService conversations = mock(EmployeeConversationService.class);
    private final EmployeeWorkspaceAccess access = mock(EmployeeWorkspaceAccess.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final List<Runnable> polls = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private EmployeeRunSubscriptions subscriptions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = new EmployeeRunSubscriptions(conversations, access, scheduler);
        when(access.current()).thenReturn(principal);
        when(conversations.subscriptionRun(principal, "app", "conv", "run")).thenReturn(view(false, ConversationRunStatus.RUNNING));
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> {
                    polls.add(call.getArgument(0));
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    futures.add(future);
                    return future;
                });
        mvc = MockMvcBuilders.standaloneSetup(new Probe(subscriptions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        subscriptions.close();
    }

    @Test
    void shouldReadSameRunAndCompleteOnlyWhenDatabaseViewIsTerminal() throws Exception {
        MvcResult response = open();
        assertTrue(response.getResponse().getContentAsString().contains("event:snapshot"));
        assertFalse(response.getResponse().getContentAsString().contains("event:done"));
        clearInvocations(conversations);
        when(conversations.subscriptionRun(principal, "app", "conv", "run")).thenReturn(view(false, ConversationRunStatus.SUCCEEDED));
        polls.get(0).run();
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        assertTrue(response.getResponse().getContentAsString().contains("event:done"));
        verify(conversations).subscriptionRun(principal, "app", "conv", "run");
        verifyNoMoreInteractions(conversations);
        verify(futures.get(0)).cancel(false);
    }

    @Test
    void shouldPublishRestrictedProjectionEvenWhenRevisionDidNotChange() throws Exception {
        MvcResult response = open();
        String before = response.getResponse().getContentAsString();
        when(conversations.subscriptionRun(principal, "app", "conv", "run")).thenReturn(view(true, ConversationRunStatus.RUNNING));
        polls.get(0).run();
        String update = response.getResponse().getContentAsString().substring(before.length());
        assertTrue(update.contains("\"restricted\":true"));
        assertTrue(update.contains("\"answer\":\"\""));
        assertFalse(update.contains("已允许的正文"));
        assertTrue(update.contains("id:run:4"));
    }

    @Test
    void shouldCloseRevokedSubscriptionWithSafeErrorAndWithoutDone() throws Exception {
        MvcResult response = open();
        when(conversations.subscriptionRun(principal, "app", "conv", "run"))
                .thenThrow(BizException.forbidden("private account details"));
        polls.get(0).run();
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        String body = response.getResponse().getContentAsString();
        assertTrue(body.contains("event:error"));
        assertTrue(body.contains("\"clear_content\":true"));
        assertFalse(body.contains("private account details"));
        assertFalse(body.contains("event:done"));
        verify(futures.get(0)).cancel(false);
    }

    @Test
    void shouldBoundSubscriptionsAndReleaseCapacityOnCompletion() throws Exception {
        List<MvcResult> opened = new ArrayList<>();
        for (int i = 0; i < 4; i++) opened.add(open());
        mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isTooManyRequests());
        assertEquals(4, polls.size());
        when(conversations.subscriptionRun(principal, "app", "conv", "run"))
                .thenReturn(view(false, ConversationRunStatus.CANCELLED));
        polls.get(0).run();
        mvc.perform(asyncDispatch(opened.get(0))).andExpect(status().isOk());
        when(conversations.subscriptionRun(principal, "app", "conv", "run"))
                .thenReturn(view(false, ConversationRunStatus.RUNNING));
        open();
        assertEquals(5, polls.size());
    }

    @Test
    void shouldReleaseShutdownResourcesWithoutAnyRunCommands() throws Exception {
        open();
        clearInvocations(conversations);
        subscriptions.close();
        polls.get(0).run();
        verifyNoMoreInteractions(conversations);
        verify(futures.get(0)).cancel(false);
        verify(scheduler).shutdownNow();
    }

    private MvcResult open() throws Exception {
        return mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andExpect(status().isOk()).andReturn();
    }

    private RunView view(boolean restricted, ConversationRunStatus status) {
        return new RunView("run", "conv", 1, "问题", restricted ? "" : "已允许的正文", List.of(),
                status, ConversationRunStage.GENERATING, "av", "v1", true, 4, 1, false,
                null, null, restricted, null, null, null);
    }

    @RestController
    static class Probe {
        private final EmployeeRunSubscriptions subscriptions;
        Probe(EmployeeRunSubscriptions subscriptions) { this.subscriptions = subscriptions; }
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() { return subscriptions.subscribe("app", "conv", "run"); }
    }
}
