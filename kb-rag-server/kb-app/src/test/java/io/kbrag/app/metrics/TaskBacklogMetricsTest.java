package io.kbrag.app.metrics;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;

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
    private TaskBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(KbTask.class);
        kbTaskMapper = mock(KbTaskMapper.class);
        registry = new SimpleMeterRegistry();
        metrics = new TaskBacklogMetrics(registry, kbTaskMapper);
    }

    @AfterEach
    void tearDown() {
        // 单元测试没有 Spring 容器，显式保留 Gauge 所属组件直到本次采集断言结束。
        Reference.reachabilityFence(metrics);
        registry.close();
    }

    @Test
    void shouldRegisterOneGaugePerOutstandingStateAndReadTheLiveCount() {
        when(kbTaskMapper.selectCount(any())).thenReturn(7L);

        // 模拟采集前发生 GC，Gauge 的弱引用不能代替 Spring 对指标组件的生命周期管理。
        System.gc();
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
