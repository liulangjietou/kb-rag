package io.kbrag.app.metrics;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the backlog gauges of the M13 contract section 3.2: the two outstanding states must be
 * registered and answer with the live row count on read, and a database that cannot answer must
 * turn into {@code NaN} - never into an exception that would take the scrape down with it.
 *
 * @author owlzhangfq@gmail.com
 */
class TaskBacklogMetricsTest {

    private KbTaskMapper kbTaskMapper;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KbTask.class);
        kbTaskMapper = mock(KbTaskMapper.class);
        registry = new SimpleMeterRegistry();
        new TaskBacklogMetrics(registry, kbTaskMapper);
    }

    @Test
    void shouldRegisterOneGaugePerOutstandingStateAndReadTheLiveCount() {
        when(kbTaskMapper.selectCount(any())).thenReturn(7L);

        assertEquals(2, registry.get(TaskBacklogMetrics.TASK_BACKLOG).gauges().size());
        assertEquals(7, gauge(TaskStatus.PENDING).value());
        assertEquals(7, gauge(TaskStatus.RUNNING).value());
    }

    @Test
    void shouldTreatANullCountAsZero() {
        when(kbTaskMapper.selectCount(any())).thenReturn(null);

        assertEquals(0, gauge(TaskStatus.PENDING).value());
    }

    @Test
    void shouldAnswerNaNInsteadOfFailingTheScrapeWhenTheDatabaseIsDown() {
        when(kbTaskMapper.selectCount(any())).thenThrow(new IllegalStateException("db down"));

        assertTrue(Double.isNaN(gauge(TaskStatus.PENDING).value()));
    }

    private Gauge gauge(TaskStatus status) {
        return registry.get(TaskBacklogMetrics.TASK_BACKLOG)
                .tag(TaskBacklogMetrics.TAG_STATUS, status.name().toLowerCase())
                .gauge();
    }
}
