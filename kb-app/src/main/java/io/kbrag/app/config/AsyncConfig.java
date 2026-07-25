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
 * Executor of the indexing pipeline.
 *
 * <p>The pool is deliberately small and backed by a bounded queue: parsing and embedding are the two
 * expensive stages of the system, and letting them run unbounded would starve the console. The task
 * decorator carries the request id into the worker thread so an upload can be traced end to end in
 * the logs.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Bean name referenced by the {@code @Async} annotation of the pipeline. */
    public static final String INDEX_EXECUTOR = "indexTaskExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 200;
    private static final String THREAD_PREFIX = "kb-index-";

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
