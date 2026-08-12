package io.kbrag.app.config;

import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.domain.config.KbProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two properties of the asynchronous configuration that nothing else can observe: that every
 * pool's declared size is the size it actually reaches, and that the request id decorator gives a thread
 * back the correlation id it arrived with.
 *
 * @author owlzhangfq@gmail.com
 */
class AsyncConfigTest {

    private static final String SUBMITTER_REQUEST_ID = "req-submitter";
    private static final String CALLER_REQUEST_ID = "req-caller";

    /** Pools declared by the configuration today; a lower count means the reflection stopped finding them. */
    private static final int MIN_EXPECTED_POOLS = 10;

    private static final int HAND_OFF_TIMEOUT_MILLIS = 5000;

    @AfterEach
    void tearDown() {
        RequestIdHolder.clear();
    }

    /**
     * Every pool must either grow to its maximum or declare only one size.
     *
     * <p>A {@link ThreadPoolTaskExecutor} grows past its core size only once the queue is <em>full</em>, so
     * a pool with both a deep queue and a larger maximum never reaches that maximum: its declared ceiling
     * is a number that cannot happen, and every reader after it believes the wrong concurrency. It has
     * shipped twice - the indexing pool's {@code core=2, max=4} behind a 200 deep queue, then the
     * evaluation pool's {@code core=2, max=6} behind a 50 deep queue while its own javadoc described six
     * runs in parallel. The rule is enumerated by reflection rather than bean by bean precisely so a pool
     * added later cannot make it three.
     */
    @Test
    void everyPoolMustEitherGrowToItsMaximumOrDeclareASingleSize() throws Exception {
        AsyncConfig config = new AsyncConfig();
        KbProperties properties = new KbProperties();
        List<ThreadPoolTaskExecutor> created = new ArrayList<>();
        try {
            for (Method method : AsyncConfig.class.getDeclaredMethods()) {
                if (method.getAnnotation(Bean.class) == null || !Executor.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) (method.getParameterCount() == 0
                        ? method.invoke(config)
                        : method.invoke(config, properties));
                created.add(executor);
                ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
                // remainingCapacity of a freshly built pool is its declared queue capacity; a zero capacity
                // pool is backed by a SynchronousQueue and reports zero, which is the unqueued shape.
                boolean unqueued = pool.getQueue().remainingCapacity() == 0;
                boolean singleSize = pool.getCorePoolSize() == pool.getMaximumPoolSize();
                assertTrue(unqueued || singleSize, method.getName() + " declares core=" + pool.getCorePoolSize()
                        + ", max=" + pool.getMaximumPoolSize() + " behind a queue of "
                        + pool.getQueue().remainingCapacity() + ", so the maximum is unreachable and the"
                        + " steady concurrency is in fact the core size");
            }
            assertTrue(created.size() >= MIN_EXPECTED_POOLS,
                    "only " + created.size() + " pools were found, the rule stopped covering the configuration");
        } finally {
            created.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    @Test
    void shouldCarryTheSubmitterRequestIdOntoAWorkerThreadAndLeaveNothingBehind() throws InterruptedException {
        RequestIdHolder.set(SUBMITTER_REQUEST_ID);
        AtomicReference<String> seenInsideTheTask = new AtomicReference<>();
        Runnable decorated = new AsyncConfig().requestIdPropagatingDecorator()
                .decorate(() -> seenInsideTheTask.set(RequestIdHolder.get()));

        AtomicReference<String> leftOnTheWorker = new AtomicReference<>("not-run");
        Thread worker = new Thread(() -> {
            // A pool worker starts with nothing of its own bound, because the previous task cleared it.
            RequestIdHolder.clear();
            decorated.run();
            leftOnTheWorker.set(RequestIdHolder.get());
        });
        worker.start();
        worker.join(HAND_OFF_TIMEOUT_MILLIS);

        assertEquals(SUBMITTER_REQUEST_ID, seenInsideTheTask.get());
        assertNull(leftOnTheWorker.get(), "a worker thread must not keep a finished task's request id");
    }

    /**
     * A task that runs on the thread that submitted it must give that thread its own request id back.
     *
     * <p>Regression guard for an unconditional {@code clear()} in the decorator's finally block. Two pools
     * here use a caller runs policy - the embedding pool and the evaluation case pool - so a full queue
     * makes the task run on the submitting thread rather than on a worker. Clearing on the way out wiped
     * the submitter's own binding, and every line that thread logged for the rest of its work lost the
     * correlation id: an indexing run or an evaluation run traced fine up to the moment its queue filled
     * and was anonymous from there on.
     */
    @Test
    void shouldRestoreTheSubmittersOwnRequestIdWhenTheTaskRunsOnTheSubmittingThread() {
        RequestIdHolder.set(CALLER_REQUEST_ID);
        AtomicReference<String> seenInsideTheTask = new AtomicReference<>();
        Runnable decorated = new AsyncConfig().requestIdPropagatingDecorator()
                .decorate(() -> seenInsideTheTask.set(RequestIdHolder.get()));

        decorated.run();

        assertEquals(CALLER_REQUEST_ID, seenInsideTheTask.get());
        assertEquals(CALLER_REQUEST_ID, RequestIdHolder.get(),
                "the caller runs policy left the submitting thread without the request id it arrived with");
    }
}
