package io.kbrag.api.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.api.dto.EmployeeRunResponse;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.app.workspace.EmployeeWorkspaceAccess;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.model.UserPrincipal;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 有界的持久运行订阅。此组件只有读取依赖，连接清理不会停止或重启问答执行。 */
@Component
public class EmployeeRunSubscriptions implements AutoCloseable {
    private static final int MAX_SUBSCRIPTIONS = 64;
    private static final int MAX_SUBSCRIPTIONS_PER_USER = 4;
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000;
    private static final long CONNECTION_TIMEOUT_MS = 120_000;
    private final EmployeeConversationService conversations;
    private final EmployeeWorkspaceAccess access;
    private final ScheduledExecutorService scheduler;
    private final Set<Subscription> active = new HashSet<>();
    private boolean closed;

    /** 订阅维护线程与模型执行线程分离，连接数限制同时约束定时任务数量。 */
    @Autowired
    public EmployeeRunSubscriptions(EmployeeConversationService conversations, EmployeeWorkspaceAccess access) {
        this(conversations, access, subscriptionPool());
    }

    EmployeeRunSubscriptions(EmployeeConversationService conversations, EmployeeWorkspaceAccess access,
                               ScheduledExecutorService scheduler) {
        this.conversations = conversations;
        this.access = access;
        this.scheduler = scheduler;
    }

    /** 初次授权失败仍返回 HTTP 错误，后续授权变化通过安全错误事件关闭当前连接。 */
    public SseEmitter subscribe(String appId, String conversationId, String runId) {
        UserPrincipal principal = access.current();
        EmployeeRunResponse initial = EmployeeRunResponse.from(
                conversations.subscriptionRun(principal, appId, conversationId, runId));
        Subscription subscription = new Subscription(principal, appId, conversationId, runId);
        synchronized (this) {
            long owned = active.stream().filter(item -> item.sameUser(principal)).count();
            if (closed || active.size() >= MAX_SUBSCRIPTIONS || owned >= MAX_SUBSCRIPTIONS_PER_USER) {
                throw new BizException(ErrorCode.RATE_LIMITED, "当前订阅过多，请关闭多余的问答标签页后重试");
            }
            active.add(subscription);
        }
        subscription.emitter.onCompletion(subscription::release);
        subscription.emitter.onTimeout(subscription::complete);
        subscription.emitter.onError(ignored -> subscription.release());
        try {
            subscription.publish(initial);
            if (!subscription.released.get()) {
                subscription.install(scheduler.scheduleWithFixedDelay(subscription::poll,
                        POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS));
            }
            return subscription.emitter;
        } catch (IOException | RuntimeException failure) {
            subscription.complete();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "暂时无法订阅回答，请重新连接");
        }
    }

    private synchronized void remove(Subscription subscription) {
        active.remove(subscription);
    }

    /** 应用停机仅释放订阅资源，运行终态由后台执行协调器负责。 */
    @Override
    @PreDestroy
    public void close() {
        Set<Subscription> subscriptions;
        synchronized (this) {
            if (closed) return;
            closed = true;
            subscriptions = Set.copyOf(active);
        }
        subscriptions.forEach(Subscription::complete);
        scheduler.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor subscriptionPool() {
        var pool = new ScheduledThreadPoolExecutor(4, task -> {
            Thread thread = new Thread(task, "kb-employee-subscription");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    private final class Subscription {
        private final UserPrincipal principal;
        private final String appId;
        private final String conversationId;
        private final String runId;
        private final SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        private final AtomicBoolean released = new AtomicBoolean();
        private ScheduledFuture<?> timer;
        private EmployeeRunResponse last;
        private long lastSentNanos;

        private Subscription(UserPrincipal principal, String appId, String conversationId, String runId) {
            this.principal = principal;
            this.appId = appId;
            this.conversationId = conversationId;
            this.runId = runId;
        }

        private void poll() {
            if (released.get()) return;
            try {
                publish(EmployeeRunResponse.from(conversations.subscriptionRun(principal, appId, conversationId, runId)));
            } catch (IOException disconnected) {
                complete();
            } catch (RuntimeException failure) {
                SubscriptionError error = SubscriptionError.from(failure);
                try {
                    emitter.send(SseEmitter.event().name("error").data(error));
                } catch (IOException | RuntimeException disconnected) {
                    // 连接已关闭时只回收订阅，不能将网络错误传给后台运行。
                } finally {
                    complete();
                }
            }
        }

        private void publish(EmployeeRunResponse snapshot) throws IOException {
            if (released.get()) return;
            // restricted 可能在 revision 不变时改变，因此不能只比较持久化序号。
            if (!snapshot.equals(last)) {
                emitter.send(SseEmitter.event().name("snapshot").id(runId + ":" + snapshot.revision()).data(snapshot));
                last = snapshot;
                lastSentNanos = System.nanoTime();
            } else if (System.nanoTime() - lastSentNanos >= TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MS)) {
                emitter.send(SseEmitter.event().comment("keep-alive"));
                lastSentNanos = System.nanoTime();
            }
            if (snapshot.status().terminal()) {
                emitter.send(SseEmitter.event().name("done").data(new RunDone(runId, snapshot.status(), snapshot.revision())));
                complete();
            }
        }

        private boolean sameUser(UserPrincipal user) {
            return principal.tenantId().equals(user.tenantId()) && principal.userId().equals(user.userId());
        }

        private synchronized void install(ScheduledFuture<?> future) {
            if (released.get()) future.cancel(false);
            else timer = future;
        }

        private synchronized void release() {
            if (!released.compareAndSet(false, true)) return;
            if (timer != null) timer.cancel(false);
            remove(this);
        }

        private void complete() {
            release();
            emitter.complete();
        }
    }

    /** done 只确认已经读取到的持久终态，不代表一定成功。 */
    private record RunDone(@JsonProperty("run_id") String runId, ConversationRunStatus status, int revision) { }

    private record SubscriptionError(String code, String message, boolean retryable,
                                     @JsonProperty("clear_content") boolean clearContent) {
        private static SubscriptionError from(RuntimeException failure) {
            if (failure instanceof BizException business) {
                ErrorCode code = business.getErrorCode();
                if (code == ErrorCode.FORBIDDEN || code == ErrorCode.UNAUTHORIZED || code == ErrorCode.NOT_FOUND) {
                    return new SubscriptionError(code.name(), "当前会话或内容已不可访问", false, true);
                }
            }
            return new SubscriptionError(ErrorCode.INTERNAL_ERROR.name(), "连接暂时中断，请重新连接", true, false);
        }
    }
}
