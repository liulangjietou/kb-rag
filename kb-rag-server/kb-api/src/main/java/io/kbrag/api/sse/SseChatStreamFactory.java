package io.kbrag.api.sse;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 管理活动 SSE 的心跳生命周期；终态、断连或应用停止都会移除调度任务。 */
@Component
public class SseChatStreamFactory {

    private static final int HEARTBEAT_THREADS = 2;
    private static final long HEARTBEAT_SECONDS = 5L;
    private final Set<SseChatStreamListener> active = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(HEARTBEAT_THREADS,
            task -> {
                Thread thread = new Thread(task, "kb-chat-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public SseChatStreamFactory() {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /** 创建已带心跳和清理回调的流，两个聊天入口共享这一生命周期约定。 */
    public synchronized SseChatStreamListener create() {
        SseChatStreamListener listener = new SseChatStreamListener();
        active.add(listener);
        try {
            var heartbeat = scheduler.scheduleWithFixedDelay(listener::heartbeat,
                    HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            listener.cancellation().onCancel(() -> {
                heartbeat.cancel(false);
                active.remove(listener);
            });
            listener.heartbeat();
            return listener;
        } catch (RuntimeException failure) {
            active.remove(listener);
            listener.close();
            throw failure;
        }
    }

    /** 应用关闭后不再保留模型调用、连接及心跳线程。 */
    @PreDestroy
    public synchronized void close() {
        active.forEach(SseChatStreamListener::close);
        scheduler.shutdownNow();
    }
}
