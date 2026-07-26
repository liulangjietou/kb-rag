package io.kbrag.app.alert;

import io.kbrag.domain.enums.AlertType;
import io.kbrag.domain.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts consecutive failures per task type and raises the first alert trigger.
 *
 * <p><b>Consecutive, not cumulative.</b> A knowledge base that ingests thousands of files will
 * accumulate failures over its lifetime, and a total count would eventually cross any threshold and
 * alert about nothing. A run of failures of the same task type is what actually indicates a broken
 * dependency, so a single success resets the counter.
 *
 * <p>Counted in memory rather than queried from the task table: the signal is about what is happening
 * now, and reading a lifetime table would need a time window that the counter expresses more directly.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFailureTracker {

    private static final String MESSAGE_TEMPLATE =
            "task type %s failed %d times in a row, last reason: %s";

    private final AlertConfigService alertConfigService;
    private final AlertService alertService;

    private final Map<TaskType, AtomicInteger> consecutiveFailures = new EnumMap<>(TaskType.class);

    /**
     * Records a failed task and alerts once the run reaches the threshold.
     *
     * @param taskType   task category
     * @param failReason classified failure cause
     */
    public void recordFailure(TaskType taskType, String failReason) {
        if (taskType == null) {
            return;
        }
        int failures = consecutiveFailures.computeIfAbsent(taskType, type -> new AtomicInteger())
                .incrementAndGet();
        int threshold = alertConfigService.current().getTaskFailThreshold();
        if (threshold <= 0 || failures < threshold) {
            return;
        }
        log.info("consecutive task failures reached the alert threshold, taskType={}, failures={}",
                taskType, failures);
        alertService.raise(AlertType.TASK_FAILURE,
                String.format(MESSAGE_TEMPLATE, taskType.name(), failures,
                        failReason == null ? "unknown" : failReason));
    }

    /**
     * Records a successful task, which ends the current run of failures.
     *
     * @param taskType task category
     */
    public void recordSuccess(TaskType taskType) {
        if (taskType == null) {
            return;
        }
        AtomicInteger counter = consecutiveFailures.get(taskType);
        if (counter != null) {
            counter.set(0);
        }
    }

    /**
     * Current run length of one task type, exposed for the tests and the diagnostics endpoint.
     *
     * @param taskType task category
     * @return number of consecutive failures
     */
    public int consecutiveFailures(TaskType taskType) {
        AtomicInteger counter = consecutiveFailures.get(taskType);
        return counter == null ? 0 : counter.get();
    }
}
