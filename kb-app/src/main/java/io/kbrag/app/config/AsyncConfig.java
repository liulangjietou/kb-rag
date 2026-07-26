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
