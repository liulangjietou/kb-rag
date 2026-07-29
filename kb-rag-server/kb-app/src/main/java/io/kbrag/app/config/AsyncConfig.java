package io.kbrag.app.config;

import io.kbrag.common.context.RequestIdHolder;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Executors of the asynchronous work.
 *
 * <p>Two pools with opposite shapes. The indexing pool is deliberately small and backed by a bounded
 * queue: parsing and embedding are the two expensive stages of the system, and letting them run
 * unbounded would starve the console. The retrieval pool is the mirror image: its tasks are short
 * model calls that a caller is already waiting on with a hard timeout, so queueing them would only
 * turn a fast degradation into a slow one, and the pool is sized to absorb concurrency instead.
 *
 * <p>The task decorator carries the request id into the worker thread so an upload or a search can be
 * traced end to end in the logs.
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Bean name referenced by the {@code @Async} annotation of the pipeline. */
    public static final String INDEX_EXECUTOR = "indexTaskExecutor";

    /** Bean name of the pool the timeout guarded retrieval stages run on. */
    public static final String RETRIEVAL_EXECUTOR = "retrievalTaskExecutor";

    /** Bean name referenced by the {@code @Async} annotation of the evaluation run submission. */
    public static final String EVAL_EXECUTOR = "evalTaskExecutor";

    /** Bean name of the pool one release gate's dual run supervision occupies. */
    public static final String GATE_EXECUTOR = "gateTaskExecutor";

    /** Bean name of the pool the open API audit rows are written on. */
    public static final String AUDIT_EXECUTOR = "auditTaskExecutor";

    /** Bean name of the pool a streamed chat generation runs on. */
    public static final String CHAT_STREAM_EXECUTOR = "chatStreamTaskExecutor";

    /** Bean name of the pool an external source scan runs on. */
    public static final String EXT_SOURCE_EXECUTOR = "extSourceTaskExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 200;
    private static final String THREAD_PREFIX = "kb-index-";

    private static final int RETRIEVAL_CORE_POOL_SIZE = 4;
    private static final int RETRIEVAL_MAX_POOL_SIZE = 16;
    private static final int RETRIEVAL_QUEUE_CAPACITY = 0;
    private static final String RETRIEVAL_THREAD_PREFIX = "kb-retrieval-";

    /**
     * One run submission can create up to 6 runs and each is handed to this pool independently, so a
     * small size is enough: the actual per-case concurrency of one run is its own bounded pool sized
     * by {@code kb.eval.concurrency}, created and torn down inside the run's own execution.
     */
    private static final int EVAL_CORE_POOL_SIZE = 2;
    private static final int EVAL_MAX_POOL_SIZE = 6;
    private static final int EVAL_QUEUE_CAPACITY = 50;
    private static final String EVAL_THREAD_PREFIX = "kb-eval-";

    /**
     * A gate thread spends its life waiting for two evaluation runs, so it must never share the pool those
     * runs execute on: a gate queued behind its own work would wait for a run that cannot start.
     */
    private static final int GATE_CORE_POOL_SIZE = 1;
    private static final int GATE_MAX_POOL_SIZE = 4;
    private static final int GATE_QUEUE_CAPACITY = 20;
    private static final String GATE_THREAD_PREFIX = "kb-gate-";

    private static final int AUDIT_CORE_POOL_SIZE = 1;
    private static final int AUDIT_MAX_POOL_SIZE = 4;
    private static final int AUDIT_QUEUE_CAPACITY = 2000;
    private static final String AUDIT_THREAD_PREFIX = "kb-audit-";

    /**
     * A streamed generation occupies a thread for the whole answer, so the pool is wide and unqueued: a queued
     * stream would leave the client staring at an open connection with no first token, which is the one thing
     * streaming exists to avoid.
     */
    private static final int CHAT_STREAM_CORE_POOL_SIZE = 2;
    private static final int CHAT_STREAM_MAX_POOL_SIZE = 16;
    private static final int CHAT_STREAM_QUEUE_CAPACITY = 0;
    private static final String CHAT_STREAM_THREAD_PREFIX = "kb-chat-stream-";

    /**
     * One scan lists and fetches many objects over slow outbound I/O, so the pool is small and the
     * queue generous: a registration or a manual sync only hands over a task and returns, and a
     * burst of triggers should wait in line rather than fan out into parallel bucket scans.
     */
    private static final int EXT_SOURCE_CORE_POOL_SIZE = 1;
    private static final int EXT_SOURCE_MAX_POOL_SIZE = 2;
    private static final int EXT_SOURCE_QUEUE_CAPACITY = 100;
    private static final String EXT_SOURCE_THREAD_PREFIX = "kb-ext-source-";

    /**
     * Creates the indexing executor.
     *
     * @return executor used by the asynchronous pipeline
     */
    @Bean(INDEX_EXECUTOR)
    public Executor indexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor that carries the timeout guarded rewrite and rerank calls.
     *
     * <p>The queue capacity is zero on purpose: a queued task would burn its caller's timeout budget
     * while waiting, so the pool grows to its maximum first and only then rejects, which surfaces as
     * an immediate degradation rather than as a stalled search.
     *
     * @return executor used by the retrieval stages
     */
    @Bean(RETRIEVAL_EXECUTOR)
    public Executor retrievalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(RETRIEVAL_CORE_POOL_SIZE);
        executor.setMaxPoolSize(RETRIEVAL_MAX_POOL_SIZE);
        executor.setQueueCapacity(RETRIEVAL_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(RETRIEVAL_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor that runs one evaluation run's execution off the submitting request.
     *
     * @return executor used by the evaluation run submission
     */
    @Bean(EVAL_EXECUTOR)
    public Executor evalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EVAL_CORE_POOL_SIZE);
        executor.setMaxPoolSize(EVAL_MAX_POOL_SIZE);
        executor.setQueueCapacity(EVAL_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EVAL_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor one release gate's dual run supervision runs on.
     *
     * @return executor used by the release gate
     */
    @Bean(GATE_EXECUTOR)
    public Executor gateTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(GATE_CORE_POOL_SIZE);
        executor.setMaxPoolSize(GATE_MAX_POOL_SIZE);
        executor.setQueueCapacity(GATE_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(GATE_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor the open API audit rows are written on.
     *
     * <p>Generously queued on purpose: an audit row must not slow a call down, and a burst of traffic should
     * fill the queue rather than push the write onto the request thread.
     *
     * @return executor used by the audit recorder
     */
    @Bean(AUDIT_EXECUTOR)
    public Executor auditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AUDIT_CORE_POOL_SIZE);
        executor.setMaxPoolSize(AUDIT_MAX_POOL_SIZE);
        executor.setQueueCapacity(AUDIT_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(AUDIT_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor a streamed chat generation runs on.
     *
     * @return executor used by the streamed chat endpoints
     */
    @Bean(CHAT_STREAM_EXECUTOR)
    public Executor chatStreamTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CHAT_STREAM_CORE_POOL_SIZE);
        executor.setMaxPoolSize(CHAT_STREAM_MAX_POOL_SIZE);
        executor.setQueueCapacity(CHAT_STREAM_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(CHAT_STREAM_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor an external source scan runs on.
     *
     * @return executor used by the asynchronous ext source sync
     */
    @Bean(EXT_SOURCE_EXECUTOR)
    public Executor extSourceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EXT_SOURCE_CORE_POOL_SIZE);
        executor.setMaxPoolSize(EXT_SOURCE_MAX_POOL_SIZE);
        executor.setQueueCapacity(EXT_SOURCE_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EXT_SOURCE_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    private TaskDecorator requestIdPropagatingDecorator() {
        return runnable -> {
            String requestId = RequestIdHolder.get();
            return () -> {
                try {
                    if (requestId != null) {
                        MDC.put(RequestIdHolder.MDC_KEY, requestId);
                    }
                    runnable.run();
                } finally {
                    RequestIdHolder.clear();
                }
            };
        };
    }
}
