package io.kbrag.app.workspace;

import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.app.openapi.KnowledgeCallCommand;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.model.UserPrincipal;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 问答执行生命周期，与浏览器连接分离；数据库提交才代表可恢复的进度。 */
@Slf4j
@Service
public class EmployeeRunCoordinator implements AutoCloseable {
    private static final long CHECKPOINT_INTERVAL_MS = 1_000;
    private static final long RECOVERY_INTERVAL_SECONDS = 30;
    private static final Duration STALE_AFTER = Duration.ofMinutes(3);
    private static final Duration MAX_RUNTIME = Duration.ofMinutes(10);
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_ANSWER_CHARACTERS = 100_000;

    private final EmployeeConversationLedger ledger;
    private final EmployeeWorkspaceAccess access;
    private final EmployeeConversationHistory history;
    private final EmployeeEvidenceService evidence;
    private final KnowledgeApiService knowledge;
    private final EmployeeConversationRunMapper runs;
    private final Executor executor;
    private final ScheduledExecutorService maintenance;
    private final Map<String, Execution> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean recoveryStarted = new AtomicBoolean();

    /** 复用现有有界聊天执行器，维护线程只负责检查点、心跳和过期中断。 */
    @Autowired
    public EmployeeRunCoordinator(EmployeeConversationLedger ledger, EmployeeWorkspaceAccess access,
                                   EmployeeConversationHistory history, EmployeeEvidenceService evidence,
                                   KnowledgeApiService knowledge, EmployeeConversationRunMapper runs,
                                   @Qualifier(AsyncConfig.CHAT_STREAM_EXECUTOR) Executor executor) {
        this(ledger, access, history, evidence, knowledge, runs, executor, maintenancePool());
    }

    EmployeeRunCoordinator(EmployeeConversationLedger ledger, EmployeeWorkspaceAccess access,
                            EmployeeConversationHistory history, EmployeeEvidenceService evidence,
                            KnowledgeApiService knowledge, EmployeeConversationRunMapper runs,
                            Executor executor, ScheduledExecutorService maintenance) {
        this.ledger = ledger;
        this.access = access;
        this.history = history;
        this.evidence = evidence;
        this.knowledge = knowledge;
        this.runs = runs;
        this.executor = executor;
        this.maintenance = maintenance;
    }

    /** 已提交的首次请求才进入执行器，重复 HTTP 请求不能再次调用模型。 */
    public void submit(EmployeeConversationScope scope, UserPrincipal principal, EmployeeConversationLedger.AcceptedRun accepted) {
        if (!accepted.created()) return;
        Execution execution = new Execution(scope, principal, accepted.run());
        if (closed.get()) {
            ledger.interruptOwned(scope, execution.conversationId, execution.runId, execution.owner);
            return;
        }
        if (active.putIfAbsent(execution.runId, execution) != null) return;
        try {
            executor.execute(() -> execute(execution));
        } catch (RejectedExecutionException rejected) {
            active.remove(execution.runId, execution);
            ledger.reject(scope, execution.conversationId, execution.runId, ErrorCode.RATE_LIMITED.name(),
                    "当前问答繁忙，请稍后重试");
        }
    }

    /** 先提交明确停止，再取消本实例上游；其他实例会在下次心跳发现终态。 */
    public void stop(EmployeeConversationScope scope, String conversationId, String runId) {
        ledger.cancel(scope, conversationId, runId);
        signalStop(runId);
    }

    /** 会话删除事务已提交后取消上游，不再读取已经软删除的会话。 */
    public void cancelCommittedRun(String runId) {
        signalStop(runId);
    }

    /** Flyway 和应用初始化完成后开始过期补偿，不自动重新执行旧问题。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startRecovery() {
        if (recoveryStarted.compareAndSet(false, true) && !closed.get()) {
            maintenance.scheduleWithFixedDelay(this::recoverSafely, 1, RECOVERY_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void execute(Execution execution) {
        UserPrincipal previous = UserContextHolder.get();
        try {
            execution.cancellation.throwIfCancelled();
            if (closed.get()) {
                ledger.interruptOwned(execution.scope, execution.conversationId, execution.runId, execution.owner);
                return;
            }
            UserPrincipal principal = access.refresh(execution.submittedBy);
            UserContextHolder.set(principal);
            access.requireTarget(principal, execution.target);
            if (!ledger.start(execution.scope, execution.conversationId, execution.runId, execution.owner)) return;
            execution.started = true;
            execution.install(maintenance.scheduleWithFixedDelay(() -> maintain(execution),
                    CHECKPOINT_INTERVAL_MS, CHECKPOINT_INTERVAL_MS, TimeUnit.MILLISECONDS));
            ModelUsageContextHolder.run(new ModelUsageContext(execution.scope.tenantId(),
                    ModelUsageContext.SOURCE_CONSOLE, execution.runId), () -> generate(execution, principal));
        } catch (RuntimeException failure) {
            saveFailure(execution, failure);
        } finally {
            execution.finishBuffer();
            active.remove(execution.runId, execution);
            if (previous == null) UserContextHolder.clear();
            else UserContextHolder.set(previous);
        }
    }

    private void generate(Execution execution, UserPrincipal principal) {
        var context = history.modelContext(principal, execution.scope, execution.conversationId, execution.turn);
        execution.sources = context.dependencies();
        KnowledgeCallCommand command = KnowledgeCallCommand.builder().appId(execution.scope.appId())
                .query(execution.question).messages(context.messages()).build();
        knowledge.employeeStream(execution.target, command,
                retrieved -> saveEvidence(execution, retrieved), execution::append, execution.cancellation);
        synchronized (execution) {
            execution.cancellation.throwIfCancelled();
            reauthorize(execution);
            execution.finishBuffer();
            ledger.succeed(execution.scope, execution.conversationId, execution.runId, execution.owner,
                    ++execution.sequence, execution.answer.toString());
        }
    }

    private void saveEvidence(Execution execution, KnowledgeCallResult retrieved) {
        UserPrincipal principal = access.refresh(execution.submittedBy);
        UserContextHolder.set(principal);
        access.requireTarget(principal, execution.target);
        var current = evidence.capture(principal, retrieved.getNodes());
        execution.sources = history.mergeSources(execution.sources, current);
        reauthorize(execution);
        String semantics = JsonUtil.toJson(Map.of("snapshot_bound", execution.target.snapshotBound(),
                "degraded", retrieved.getDegraded() == null ? List.of() : retrieved.getDegraded(),
                "routed_kb_ids", retrieved.routedKbIds()));
        boolean saved = ledger.retrieved(execution.scope, execution.conversationId, execution.runId, execution.owner,
                JsonUtil.toJson(execution.sources), semantics, retrieved.getDegraded() != null && !retrieved.getDegraded().isEmpty());
        if (!saved) execution.cancellation.cancel();
        execution.cancellation.throwIfCancelled();
        execution.retrieved = true;
    }

    private void maintain(Execution execution) {
        try {
            synchronized (execution) {
                if (execution.finished) return;
                if (System.nanoTime() - execution.startedNanos > MAX_RUNTIME.toNanos()) {
                    throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "employee run exceeded time budget");
                }
                reauthorize(execution);
                boolean saved;
                if (execution.retrieved && execution.answer.length() != execution.savedLength) {
                    saved = ledger.checkpoint(execution.scope, execution.conversationId, execution.runId,
                            execution.owner, ++execution.sequence, execution.answer.toString());
                    if (saved) execution.savedLength = execution.answer.length();
                } else {
                    saved = ledger.heartbeat(execution.scope, execution.conversationId, execution.runId, execution.owner);
                }
                if (!saved) execution.cancellation.cancel();
            }
        } catch (RuntimeException failure) {
            saveFailure(execution, failure);
        }
    }

    private void reauthorize(Execution execution) {
        UserPrincipal principal = access.refresh(execution.submittedBy);
        access.requireTarget(principal, execution.target);
        if (!evidence.canReadAll(principal, execution.sources)) {
            throw BizException.forbidden("employee evidence is no longer accessible");
        }
    }

    private void saveFailure(Execution execution, RuntimeException original) {
        synchronized (execution) {
            if (execution.failureHandled) return;
            execution.failureHandled = true;
            EmployeeRunFailure failure = EmployeeRunFailure.from(original);
            execution.finishBuffer();
            execution.cancellation.cancel();
            if (execution.started && execution.retrieved && execution.answer.length() > execution.savedLength) {
                try {
                    ledger.checkpoint(execution.scope, execution.conversationId, execution.runId, execution.owner,
                            ++execution.sequence, execution.answer.toString());
                } catch (RuntimeException checkpointFailure) {
                    // 正文检查点失败后仍尝试短小的终态事务，避免仅因正文写入失败而占住会话。
                    log.error("employee failure checkpoint could not be persisted, errorCode={}, runId={}, failureType={}",
                            ErrorCode.INTERNAL_ERROR, execution.runId, checkpointFailure.getClass().getSimpleName());
                }
            }
            try {
                if (execution.started) {
                    ledger.fail(execution.scope, execution.conversationId, execution.runId, execution.owner,
                            failure.code().name(), failure.message());
                } else {
                    ledger.reject(execution.scope, execution.conversationId, execution.runId,
                            failure.code().name(), failure.message());
                }
            } catch (RuntimeException persistenceFailure) {
                // 数据库尚未恢复时交由过期补偿中断，禁止凭内存成功状态向连接报告 done。
                log.error("employee run failure could not be persisted, errorCode={}, runId={}, failureType={}",
                        ErrorCode.INTERNAL_ERROR, execution.runId, persistenceFailure.getClass().getSimpleName());
            }
        }
    }

    private void recoverSafely() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(STALE_AFTER);
            for (var address : runs.staleAddresses(cutoff, RECOVERY_BATCH_SIZE)) {
                try {
                    if (ledger.interruptIfStale(address.scope(), address.conversationId(), address.runId(), cutoff)) {
                        signalStop(address.runId());
                    }
                } catch (RuntimeException failure) {
                    log.error("employee run recovery failed, errorCode={}, runId={}, failureType={}",
                            ErrorCode.INTERNAL_ERROR, address.runId(), failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException failure) {
            log.error("employee run recovery scan failed, errorCode={}, failureType={}",
                    ErrorCode.INTERNAL_ERROR, failure.getClass().getSimpleName());
        }
    }

    private void signalStop(String runId) {
        Execution execution = active.get(runId);
        if (execution != null) execution.cancellation.cancel();
    }

    /** 关闭维护资源并持久化本实例中断状态；不会关闭共享模型执行器。 */
    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Execution execution : active.values()) {
            try {
                ledger.interruptOwned(execution.scope, execution.conversationId, execution.runId, execution.owner);
            } catch (RuntimeException failure) {
                log.error("employee shutdown state could not be persisted, errorCode={}, runId={}",
                        ErrorCode.INTERNAL_ERROR, execution.runId);
            } finally {
                execution.cancellation.cancel();
                execution.finishBuffer();
            }
        }
        maintenance.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor maintenancePool() {
        AtomicInteger sequence = new AtomicInteger();
        var pool = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "kb-employee-maintenance-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    /** 只保存本次执行的有界内存缓冲，不作为历史或终态的事实来源。 */
    private static final class Execution {
        private final EmployeeConversationScope scope;
        private final UserPrincipal submittedBy;
        private final String conversationId;
        private final String runId;
        private final String owner = UUID.randomUUID().toString();
        private final int turn;
        private final String question;
        private final EmployeeRunTarget target;
        private final ChatCancellation cancellation = new ChatCancellation();
        private final StringBuilder answer = new StringBuilder();
        private final long startedNanos = System.nanoTime();
        private volatile List<EmployeeCitation> sources = List.of();
        private volatile boolean started;
        private volatile boolean retrieved;
        private ScheduledFuture<?> timer;
        private boolean finished;
        private boolean failureHandled;
        private long sequence;
        private int savedLength;

        private Execution(EmployeeConversationScope scope, UserPrincipal principal, EmployeeConversationRun run) {
            this.scope = scope;
            submittedBy = principal;
            conversationId = run.getConversationId();
            runId = run.getRunId();
            turn = run.getTurnNo();
            question = run.getQuestion();
            target = JsonUtil.parse(run.getTargetJson(), EmployeeRunTarget.class);
        }

        private synchronized void append(String delta) {
            cancellation.throwIfCancelled();
            if (finished) return;
            if (answer.length() + delta.length() > MAX_ANSWER_CHARACTERS) {
                throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "employee answer exceeded content limit");
            }
            answer.append(delta);
        }

        private synchronized void install(ScheduledFuture<?> future) {
            if (finished) future.cancel(false);
            else timer = future;
        }

        private synchronized void finishBuffer() {
            finished = true;
            if (timer != null) timer.cancel(false);
        }
    }
}
