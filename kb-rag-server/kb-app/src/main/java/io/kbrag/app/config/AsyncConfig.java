package io.kbrag.app.config;

import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.domain.config.KbProperties;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executors of the asynchronous work.
 *
 * <p>Two pools with opposite shapes. The indexing pool is deliberately small and backed by a bounded
 * queue: parsing and embedding are the two expensive stages of the system, and letting them run
 * unbounded would starve the console. The retrieval pool is the mirror image: its tasks are short
 * model calls that a caller is already waiting on with a hard timeout, so queueing them would only
 * turn a fast degradation into a slow one, and the pool is sized to absorb concurrency instead.
 *
 * <p>The pools are split by how long a task holds its thread, not by which module submitted it. That is
 * why the graph extraction has its own: it is the one task measured in hours, and sharing the indexing
 * pool made every upload queue behind it.
 *
 * <p>Two levels of concurrency stack here and the product is what an upstream service sees. The indexing
 * pool decides how many documents are in flight; inside one document the embedding batches fan out again
 * over their own pool. The embedding pool is therefore a global ceiling rather than a per document one -
 * see {@link #embedTaskExecutor}.
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

    /** Bean name of the pool a knowledge graph extraction runs on. */
    public static final String GRAPH_EXECUTOR = "graphTaskExecutor";

    /** Bean name of the pool the embedding batches of one document are spread over. */
    public static final String EMBED_EXECUTOR = "embedTaskExecutor";

    /**
     * Documents indexed at once.
     *
     * <p>Core and max are equal on purpose. A {@link ThreadPoolTaskExecutor} only grows past its core
     * size once the queue is <em>full</em>, so with a 200 deep queue the old {@code core=2, max=4} meant
     * the pool never left 2 - the max was unreachable configuration, and a batch upload of fifty files
     * was processed two at a time. Stating one number makes the actual concurrency the one that is
     * written down.
     *
     * <p>Four rather than the core count of the machine: this thread spends its life waiting on the
     * parser service, the embedding provider and the engines, not on local CPU. The splitting stage is
     * the only in-process work and it is cheap next to those round trips.
     */
    private static final int CORE_POOL_SIZE = 4;
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
     * A graph extraction of a large corpus occupies its thread for hours, so it cannot share the
     * indexing pool: two of them would hold half of it and an upload would sit in the queue behind work
     * that finishes some time tomorrow. The size is small because each task fans its model calls out
     * internally by {@code kb.graph.extract-concurrency} - this bounds how many corpora are extracted at
     * once, and the product of the two is what the model provider sees.
     */
    private static final int GRAPH_CORE_POOL_SIZE = 1;
    private static final int GRAPH_MAX_POOL_SIZE = 2;
    private static final int GRAPH_QUEUE_CAPACITY = 50;
    private static final String GRAPH_THREAD_PREFIX = "kb-graph-task-";

    private static final int EMBED_QUEUE_CAPACITY = 500;
    private static final String EMBED_THREAD_PREFIX = "kb-embed-";

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

    /**
     * Creates the executor a knowledge graph extraction runs on.
     *
     * @return executor used by the graph extraction task
     */
    @Bean(GRAPH_EXECUTOR)
    public Executor graphTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(GRAPH_CORE_POOL_SIZE);
        executor.setMaxPoolSize(GRAPH_MAX_POOL_SIZE);
        executor.setQueueCapacity(GRAPH_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(GRAPH_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor the embedding batches of one document are spread over.
     *
     * <p>Sized from configuration and shared on purpose: the point of the pool is a <em>global</em>
     * ceiling on how many embedding requests are in flight, so several documents indexing at once
     * cannot together exceed what the provider's rate limit tolerates. The caller runs policy is the
     * back pressure - a full queue makes the submitting pipeline thread embed a batch itself, which
     * slows the producer down instead of dropping a batch and losing a vector.
     *
     * @param properties knowledge base configuration, source of the concurrency
     * @return executor used by the chunk embedder
     */
    @Bean(EMBED_EXECUTOR)
    public Executor embedTaskExecutor(KbProperties properties) {
        int concurrency = Math.max(1, properties.getEmbedding().getConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(EMBED_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EMBED_THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
