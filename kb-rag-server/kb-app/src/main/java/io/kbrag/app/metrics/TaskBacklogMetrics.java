package io.kbrag.app.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Task backlog gauges, the M13 contract section 3.2.
 *
 * <p><b>Queried on scrape, not kept as running counters.</b> The task table already holds the
 * truth and {@code idx_status} makes the two counts index-only lookups; a second incrementally
 * maintained number would be one more thing that can disagree with it. A Prometheus scrape happens
 * every few seconds at worst, which is nothing next to the writes the pipeline itself performs.
 *
 * <p><b>A failing database must not fail the scrape.</b> The count falls back to {@code NaN},
 * which Prometheus treats as "no sample this round" - the metrics endpoint stays up precisely when
 * an operator needs it to diagnose the outage.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class TaskBacklogMetrics {

    /** Gauge of tasks sitting in one lifecycle state. */
    static final String TASK_BACKLOG = "kb.task.backlog";

    static final String TAG_STATUS = "status";

    private final KbTaskMapper kbTaskMapper;

    public TaskBacklogMetrics(MeterRegistry registry, KbTaskMapper kbTaskMapper) {
        this.kbTaskMapper = kbTaskMapper;
        // Only the states that mean outstanding work: a SUCCESS/FAILED row is history, not backlog.
        register(registry, TaskStatus.PENDING);
        register(registry, TaskStatus.RUNNING);
    }

    private void register(MeterRegistry registry, TaskStatus status) {
        Gauge.builder(TASK_BACKLOG, this, metrics -> metrics.count(status))
                .tag(TAG_STATUS, status.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    /**
     * Live count of the tasks in one state, {@code NaN} when the database cannot answer.
     *
     * @param status lifecycle state
     * @return row count or {@code NaN}
     */
    double count(TaskStatus status) {
        try {
            Long count = kbTaskMapper.selectCount(new LambdaQueryWrapper<KbTask>()
                    .eq(KbTask::getStatus, status));
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.error("task backlog gauge query failed, errorCode={}, status={}",
                    ErrorCode.INTERNAL_ERROR, status, e);
            return Double.NaN;
        }
    }
}
