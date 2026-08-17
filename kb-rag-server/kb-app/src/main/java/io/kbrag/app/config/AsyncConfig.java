package io.kbrag.app.config;

import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.model.ModelUsageContext;
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
 * <p><b>One shape rule governs every pool below: either the queue is empty, or core equals max.</b> A
 * {@link ThreadPoolTaskExecutor} only grows past its core size once the queue is <em>full</em>, so a pool
 * with both a deep queue and a larger max never reaches that max and its declared ceiling is a lie the
 * next reader believes - the old indexing {@code core=2, max=4} behind a 200 deep queue never left 2, and
 * a batch upload of fifty files was processed two at a time. The two unqueued pools (retrieval and chat
 * stream) are the deliberate exception: with no queue to fill, growth to max is the <em>first</em> thing
 * that happens, which is exactly the "absorb the burst, then reject" shape they want. Everywhere else the
 * core size is the concurrency that actually happens, and there is only one number to read.
 * {@code AsyncConfigTest} pins this rule so the third occurrence cannot ship.
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

    /** Bean name of the pool one evaluation run's execution is submitted to. */
    public static final String EVAL_EXECUTOR = "evalTaskExecutor";

    /** Bean name of the pool the cases of the running evaluations are spread over. */
    public static final String EVAL_CASE_EXECUTOR = "evalCaseTaskExecutor";

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

    /** Queue depth of the indexing pool, see the shape rule in the class comment. */
    private static final int QUEUE_CAPACITY = 200;
    private static final String THREAD_PREFIX = "kb-index-";

    private static final int RETRIEVAL_CORE_POOL_SIZE = 4;
    private static final int RETRIEVAL_MAX_POOL_SIZE = 16;
    private static final int RETRIEVAL_QUEUE_CAPACITY = 0;
    private static final String RETRIEVAL_THREAD_PREFIX = "kb-retrieval-";

    /**
     * One run submission can create up to 6 runs and each is handed to this pool independently, so the
     * size is that same 6: a thread here only supervises one run, and the cases themselves fan out again
     * over {@link #evalCaseTaskExecutor}, which is where the ceiling on model calls actually lives. Sizing
     * this pool below the largest submission would serialise a matrix the console presents as one action -
     * and it did, silently, while {@code core=2, max=6} behind a 50 deep queue read like 6.
     */
    private static final int EVAL_POOL_SIZE = 6;
    private static final int EVAL_QUEUE_CAPACITY = 50;
    private static final String EVAL_THREAD_PREFIX = "kb-eval-";

    /**
     * A case thread waits for the retrieval stages, so it must not share the pool the runs supervising
     * it occupy: a case queued behind its own run would wait for a run that cannot finish.
     */
    private static final int EVAL_CASE_QUEUE_CAPACITY = 500;
    private static final String EVAL_CASE_THREAD_PREFIX = "kb-eval-case-";

    /**
     * A gate thread spends its life waiting for two evaluation runs, so it must never share the pool those
     * runs execute on: a gate queued behind its own work would wait for a run that cannot start.
     *
     * <p>Four and not one, because a queued gate is not a delayed gate but a stalled release: the version
     * sits in {@code GATING} the whole time it waits, and the console shows a release in progress that has
     * not begun. The threads are cheap - they only poll - and the work they wait on is already capped by
     * the evaluation pool.
     */
    private static final int GATE_POOL_SIZE = 4;
    private static final int GATE_QUEUE_CAPACITY = 20;
    private static final String GATE_THREAD_PREFIX = "kb-gate-";

    /**
     * One thread, which is what this pool has always effectively had: an audit row is a single write and
     * the queue in front of it is the burst absorber. The former {@code max=4} was never reached behind a
     * 2000 deep queue, so naming the real number here changes nothing but what the next reader believes.
     */
    private static final int AUDIT_POOL_SIZE = 1;
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
     * burst of triggers should wait in line rather than fan out into parallel bucket scans. One thread is
     * what that description means and what the pool already did - the former {@code max=2} sat behind a 100
     * deep queue and was never reached.
     */
    private static final int EXT_SOURCE_POOL_SIZE = 1;
    private static final int EXT_SOURCE_QUEUE_CAPACITY = 100;
    private static final String EXT_SOURCE_THREAD_PREFIX = "kb-ext-source-";

    /**
     * A graph extraction of a large corpus occupies its thread for hours, so it cannot share the
     * indexing pool: two of them would hold half of it and an upload would sit in the queue behind work
     * that finishes some time tomorrow. The size is small because each task fans its model calls out
     * internally by {@code kb.graph.extract-concurrency} - this bounds how many corpora are extracted at
     * once, and the product of the two is what the model provider sees.
     */
    private static final int GRAPH_QUEUE_CAPACITY = 50;
    private static final String GRAPH_THREAD_PREFIX = "kb-graph-task-";

    private static final int EMBED_QUEUE_CAPACITY = 500;
    private static final String EMBED_THREAD_PREFIX = "kb-embed-";

    /**
     * Creates the indexing executor, sized from configuration.
     *
     * <p>Configurable because the right number depends on the deployment, not on the code: this thread
     * spends its life waiting on the parser service, the embedding provider and the engines, so the
     * ceiling is what those can absorb rather than the local core count. A 10 core host running the
     * engines alongside the application wants a different number than a laptop.
     *
     * @param properties knowledge base configuration, source of the concurrency
     * @return executor used by the asynchronous pipeline
     */
    @Bean(INDEX_EXECUTOR)
    public Executor indexTaskExecutor(KbProperties properties) {
        int concurrency = Math.max(1, properties.getIndex().getConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
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
     * <p>The default abort policy is kept rather than replaced by a caller runs one: a rejected run must
     * not be dragged back onto the submitting HTTP request, which is the exact shape the {@code @Async}
     * self invocation used to produce. The rejection is handled where the run row is, in
     * {@code EvalRunService}, which turns it into a {@code FAILED} run with a reason instead of leaving a
     * {@code PENDING} row nothing will ever pick up.
     *
     * @return executor used by the evaluation run submission
     */
    @Bean(EVAL_EXECUTOR)
    public Executor evalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EVAL_POOL_SIZE);
        executor.setMaxPoolSize(EVAL_POOL_SIZE);
        executor.setQueueCapacity(EVAL_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EVAL_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor the cases of the running evaluations are spread over, sized from configuration.
     *
     * <p>Shared across the runs rather than created per run: the cases are what actually call the embedding,
     * rerank and judge providers, and they reach them through the very retrieval pool the online search uses.
     * A pool per run made the peak load the product of the two concurrencies - six runs of a submitted matrix
     * fanning out at {@code kb.eval.concurrency} each - which an offline report has no business imposing on
     * the served traffic. One pool makes the setting the ceiling it reads like.
     *
     * <p>The caller runs policy is the back pressure: a full queue makes the supervising run thread judge a
     * case itself, which slows the producer down instead of failing a run whose data set is simply large. It
     * cannot deadlock, because that thread is waiting on these very cases and never on itself.
     *
     * @param properties knowledge base configuration, source of the concurrency
     * @return executor used by the evaluation case execution
     */
    @Bean(EVAL_CASE_EXECUTOR)
    public Executor evalCaseTaskExecutor(KbProperties properties) {
        int concurrency = Math.max(1, properties.getEval().getConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(EVAL_CASE_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EVAL_CASE_THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor one release gate's dual run supervision runs on.
     *
     * <p>Abort on rejection, for the same reason as the evaluation pool and with the same obligation on
     * the submitter: {@code ReleaseGateService} marked the version {@code GATING} before handing the gate
     * over, and a rejection it swallowed would leave that version gating forever - a state its own release
     * guard refuses to release out of.
     *
     * @return executor used by the release gate
     */
    @Bean(GATE_EXECUTOR)
    public Executor gateTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(GATE_POOL_SIZE);
        executor.setMaxPoolSize(GATE_POOL_SIZE);
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
        executor.setCorePoolSize(AUDIT_POOL_SIZE);
        executor.setMaxPoolSize(AUDIT_POOL_SIZE);
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
        executor.setCorePoolSize(EXT_SOURCE_POOL_SIZE);
        executor.setMaxPoolSize(EXT_SOURCE_POOL_SIZE);
        executor.setQueueCapacity(EXT_SOURCE_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EXT_SOURCE_THREAD_PREFIX);
        executor.setTaskDecorator(requestIdPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Creates the executor a knowledge graph extraction runs on, sized from configuration.
     *
     * <p>This is how many extractions run at once; how many model calls one extraction fans out to is
     * {@code kb.graph.extract-concurrency}. The two multiply into the peak load on the chat model, which
     * is the tighter rate limit of the two providers - raise this one knowing that.
     *
     * @param properties knowledge base configuration, source of the concurrency
     * @return executor used by the graph extraction task
     */
    @Bean(GRAPH_EXECUTOR)
    public Executor graphTaskExecutor(KbProperties properties) {
        int concurrency = Math.max(1, properties.getGraph().getTaskConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
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

    /**
     * Carries the submitter's request id onto the thread the task ends up running on.
     *
     * <p><b>It restores rather than clears, because the task does not always run on a worker thread.</b>
     * Two pools here use a caller runs policy, so a full queue makes the task run on the very thread that
     * submitted it - an indexing thread, or an evaluation run supervising its own cases. Clearing
     * unconditionally in the finally block wiped that thread's own request id on the way out, and every
     * line it logged afterwards lost the correlation id halfway through the work. Saving what was bound
     * before and putting it back covers both cases: on a worker thread nothing was bound, so restoring
     * null is the clear that was always intended.
     *
     * <p>Package private so {@code AsyncConfigTest} can exercise both hand-off shapes directly rather than
     * having to fill a 500 deep queue to reach the second one.
     *
     * @return decorator applied to every pool of this configuration
     */
    TaskDecorator requestIdPropagatingDecorator() {
        return runnable -> {
            // Read on the submitting thread: TaskDecorator#decorate runs at submission time.
            String submittedRequestId = RequestIdHolder.get();
            ModelUsageContext submittedUsageContext = ModelUsageContextHolder.get();
            return () -> {
                String previous = RequestIdHolder.get();
                ModelUsageContext previousUsageContext = ModelUsageContextHolder.get();
                if (submittedRequestId != null) {
                    MDC.put(RequestIdHolder.MDC_KEY, submittedRequestId);
                }
                ModelUsageContextHolder.set(submittedUsageContext);
                try {
                    runnable.run();
                } finally {
                    ModelUsageContextHolder.set(previousUsageContext);
                    if (previous == null) {
                        RequestIdHolder.clear();
                    } else {
                        MDC.put(RequestIdHolder.MDC_KEY, previous);
                    }
                }
            };
        };
    }
}
