package io.kbrag.app.workspace;

import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunAddress;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.enums.UserSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 执行与持久化/取消的边界测试，定时任务由测试显式推进以避免依赖睡眠。 */
class EmployeeRunCoordinatorTest {
    private static final EmployeeConversationScope SCOPE = new EmployeeConversationScope("tenant", "user", "app");
    private final UserPrincipal principal = new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of("role"), Set.of("app:use"), true, Set.of(), true, Set.of());
    private final EmployeeConversationLedger ledger = mock(EmployeeConversationLedger.class);
    private final EmployeeWorkspaceAccess access = mock(EmployeeWorkspaceAccess.class);
    private final EmployeeConversationHistory history = mock(EmployeeConversationHistory.class);
    private final EmployeeEvidenceService evidence = mock(EmployeeEvidenceService.class);
    private final KnowledgeApiService knowledge = mock(KnowledgeApiService.class);
    private final EmployeeConversationRunMapper runs = mock(EmployeeConversationRunMapper.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final Queue<Runnable> jobs = new ConcurrentLinkedQueue<>();
    private final List<Runnable> timers = new ArrayList<>();
    private EmployeeRunCoordinator coordinator;
    private EmployeeConversationLedger.AcceptedRun accepted;

    @BeforeEach
    void setUp() {
        coordinator = new EmployeeRunCoordinator(ledger, access, history, evidence, knowledge, runs, jobs::add, scheduler);
        var conversation = EmployeeConversation.create("conv", SCOPE, "问答", LocalDateTime.now());
        var target = new EmployeeRunTarget("app", "av_original", "V1.0", "{}", null, null, false);
        var run = EmployeeConversationRun.pending("run", conversation, SCOPE, "request", "hash", "问题", target, 1);
        accepted = new EmployeeConversationLedger.AcceptedRun(run, true);
        when(access.refresh(principal)).thenReturn(principal);
        when(history.modelContext(any(), any(), anyString(), anyInt())).thenReturn(
                new EmployeeConversationHistory.ModelContext(List.of(), List.of(source("prior").asInherited())));
        doCallRealMethod().when(history).mergeSources(anyList(), anyList());
        when(evidence.capture(any(), anyList())).thenReturn(List.of(source("current")));
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
        when(ledger.start(any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(ledger.retrieved(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(ledger.checkpoint(any(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(true);
        when(ledger.heartbeat(any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(ledger.succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> {
                    timers.add(call.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
    }

    @AfterEach
    void close() {
        coordinator.close();
        UserContextHolder.clear();
        ModelUsageContextHolder.clear();
    }

    @Test
    void shouldExecuteOnceAndBindUserAndBillingUntilEvidenceAndAnswerCommit() {
        UserPrincipal previous = new UserPrincipal("old", "old", "old", "旧", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(), false, Set.of());
        ModelUsageContext previousUsage = new ModelUsageContext("old", "INTERNAL", "old");
        UserContextHolder.set(previous);
        ModelUsageContextHolder.set(previousUsage);
        doAnswer(call -> {
            assertSame(principal, UserContextHolder.get());
            assertEquals(new ModelUsageContext("tenant", ModelUsageContext.SOURCE_CONSOLE, "run"), ModelUsageContextHolder.get());
            retrieved(call.getArgument(2));
            call.<Consumer<String>>getArgument(3).accept("第一句");
            timers.get(0).run();
            call.<Consumer<String>>getArgument(3).accept("。最终回答");
            return null;
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        coordinator.submit(SCOPE, principal, accepted);
        coordinator.submit(SCOPE, principal, new EmployeeConversationLedger.AcceptedRun(accepted.run(), false));
        assertEquals(1, jobs.size());
        jobs.remove().run();
        assertSame(previous, UserContextHolder.get());
        assertSame(previousUsage, ModelUsageContextHolder.get());
        verify(knowledge, times(1)).employeeStream(any(), any(), any(), any(), any());
        ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        var ordered = inOrder(ledger);
        ordered.verify(ledger).start(eq(SCOPE), eq("conv"), eq("run"), anyString());
        ordered.verify(ledger).retrieved(eq(SCOPE), eq("conv"), eq("run"), anyString(), references.capture(), anyString(), eq(false));
        ordered.verify(ledger).checkpoint(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq(1L), eq("第一句"));
        ordered.verify(ledger).succeed(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq(2L), eq("第一句。最终回答"));
        assertTrue(references.getValue().contains("prior"));
        assertTrue(references.getValue().contains("current"));
    }

    @Test
    void shouldAvoidModelCallWhenAnotherWorkerAlreadyClaimedRun() {
        when(ledger.start(any(), anyString(), anyString(), anyString())).thenReturn(false);
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        verifyNoInteractions(knowledge, history, evidence);
    }

    @Test
    void shouldPersistRejectionWhenExecutorIsFull() {
        coordinator.close();
        coordinator = new EmployeeRunCoordinator(ledger, access, history, evidence, knowledge, runs,
                job -> { throw new RejectedExecutionException(); }, scheduler);
        coordinator.submit(SCOPE, principal, accepted);
        verify(ledger).reject(eq(SCOPE), eq("conv"), eq("run"), eq(ErrorCode.RATE_LIMITED.name()), anyString());
        verifyNoInteractions(knowledge);
    }

    @Test
    void shouldStopBeforeGenerationWhenReferencesCannotBeSaved() {
        when(ledger.retrieved(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "sensitive upstream detail"));
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            throw new AssertionError("generation must not begin");
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        verify(ledger, never()).succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(ledger).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("INTERNAL_ERROR"), message.capture());
        assertFalse(message.getValue().contains("sensitive"));
    }

    @Test
    void shouldSaveStopBeforeCancellingUpstreamAndNeverMarkSuccess() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            ChatCancellation cancellation = call.getArgument(4);
            cancellation.onCancel(() -> {
                verify(ledger).cancel(SCOPE, "conv", "run");
                cancelled.countDown();
            });
            started.countDown();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            cancellation.throwIfCancelled();
            return null;
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(jobs.remove());
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            coordinator.stop(SCOPE, "conv", "run");
            worker.get(5, TimeUnit.SECONDS);
        } finally {
            cancelled.countDown();
        }
        verify(ledger, never()).succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldPreservePartialContentOnProviderFailureWithoutSavingRawException() {
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            call.<Consumer<String>>getArgument(3).accept("部分回答");
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "provider password=secret");
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        verify(ledger).checkpoint(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq(1L), eq("部分回答"));
        verify(ledger).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("UPSTREAM_MODEL_ERROR"), eq("生成服务暂不可用，请稍后重试"));
        verify(ledger, never()).succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldPersistFailureEvenWhenPartialCheckpointCannotBeSaved() {
        when(ledger.checkpoint(any(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("checkpoint write failed"));
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            call.<Consumer<String>>getArgument(3).accept("部分回答");
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "provider unavailable");
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        verify(ledger).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("UPSTREAM_MODEL_ERROR"), anyString());
        verify(ledger, never()).succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldPersistRevocationBeforeBlockedProviderReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ChatCancellation> signal = new AtomicReference<>();
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            signal.set(call.getArgument(4));
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            signal.get().throwIfCancelled();
            return null;
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(jobs.remove());
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            when(evidence.canReadAll(any(), anyList())).thenReturn(false);
            timers.get(0).run();
            assertTrue(signal.get().isCancelled());
            assertFalse(worker.isDone());
            verify(ledger).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("FORBIDDEN"), anyString());
        } finally {
            release.countDown();
            worker.get(5, TimeUnit.SECONDS);
        }
        verify(ledger, times(1)).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("FORBIDDEN"), anyString());
        verify(ledger, never()).succeed(any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldRejectRevokedIdentityBeforeClaimOrModelCall() {
        when(access.refresh(principal)).thenThrow(BizException.forbidden("revoked"));
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        verify(ledger).reject(eq(SCOPE), eq("conv"), eq("run"), eq("FORBIDDEN"), anyString());
        verify(ledger, never()).start(any(), anyString(), anyString(), anyString());
        verifyNoInteractions(knowledge);
    }

    @Test
    void shouldCancelOnRevocationDuringGeneration() {
        AtomicReference<ChatCancellation> signal = new AtomicReference<>();
        doAnswer(call -> {
            retrieved(call.getArgument(2));
            signal.set(call.getArgument(4));
            when(evidence.canReadAll(any(), anyList())).thenReturn(false);
            timers.get(0).run();
            signal.get().throwIfCancelled();
            return null;
        }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
        coordinator.submit(SCOPE, principal, accepted);
        jobs.remove().run();
        assertNotNull(signal.get());
        assertTrue(signal.get().isCancelled());
        verify(ledger).fail(eq(SCOPE), eq("conv"), eq("run"), anyString(), eq("FORBIDDEN"), anyString());
    }

    @Test
    void shouldPersistInterruptedStateForQueuedRunOnShutdown() {
        coordinator.submit(SCOPE, principal, accepted);
        coordinator.close();
        jobs.remove().run();
        verify(ledger).interruptOwned(eq(SCOPE), eq("conv"), eq("run"), anyString());
        verifyNoInteractions(knowledge);
        verify(scheduler).shutdownNow();
    }

    @Test
    void shouldScanStaleRunsWithoutStartingOrRegeneratingThem() {
        when(runs.staleAddresses(any(), anyInt())).thenReturn(List.of(new EmployeeRunAddress("tenant", "user", "app", "conv", "run")));
        coordinator.startRecovery();
        coordinator.startRecovery();
        assertEquals(1, timers.size());
        timers.get(0).run();
        verify(ledger).interruptIfStale(eq(SCOPE), eq("conv"), eq("run"), any(LocalDateTime.class));
        verifyNoInteractions(knowledge);
        assertTrue(jobs.isEmpty());
    }

    private void retrieved(Consumer<KnowledgeCallResult> callback) {
        callback.accept(KnowledgeCallResult.builder().nodes(List.of()).degraded(List.of()).build());
    }

    private EmployeeCitation source(String id) {
        return new EmployeeCitation(id, "version_" + id, "chunk_" + id, "kb", "材料", "1.0",
                null, null, null, null, null, "原文", false);
    }
}
