package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.alert.TaskFailureTracker;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The state machine of one {@code t_kb_task} row.
 *
 * <p>Split out of {@link IndexPipelineService} because a task's states are orthogonal to what the task is
 * building: start, progress, succeed, fail is the same ladder whether the run is a first build, a rebuild,
 * a restore or a confirmation. The three collaborators it needs - the task table, the id generator and the
 * failure tracker - exist in the pipeline for this ladder and nothing else.
 *
 * <p><b>What stays outside.</b> A failed build also has to move the document's processing status and the
 * version's status, which belong to two other aggregates. Those writes stay with the pipeline, and only
 * the task half of the failure is asked of this class - one aggregate per owner, rather than a "task"
 * object that quietly updates documents.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexTaskLifecycle {

    /** Progress of a finished task. */
    private static final int PROGRESS_DONE = 100;

    /** Column width of {@code fail_reason}; a longer cause is stored truncated rather than rejected. */
    private static final int FAIL_REASON_MAX_LENGTH = 1024;

    private final KbTaskMapper kbTaskMapper;
    private final BizIdGenerator bizIdGenerator;
    private final TaskFailureTracker taskFailureTracker;

    /**
     * Cuts a failure cause down to what the column can hold.
     *
     * <p>Exposed as a static helper because the same string is written to three tables by three different
     * owners; truncating it in only one of them would leave the console showing two different causes for
     * one failure.
     *
     * @param reason classified failure cause, may be {@code null}
     * @return the cause, truncated when it exceeds the column
     */
    public static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > FAIL_REASON_MAX_LENGTH ? reason.substring(0, FAIL_REASON_MAX_LENGTH) : reason;
    }

    /**
     * Opens the task of a build, reusing the row of a previous attempt when there is one.
     *
     * <p>A retry increments {@code retry_count} on the existing row rather than inserting a second one: an
     * operator asking "how did this version build" must get one answer, and the explicit update is what
     * lets a rerun clear the {@code fail_reason} an entity based update would skip.
     *
     * @param versionId version being built, the task's business id
     * @param taskType  kind of build
     * @return the running task row
     */
    public KbTask start(String versionId, TaskType taskType) {
        KbTask task = kbTaskMapper.selectOne(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getBizId, versionId)
                .eq(KbTask::getTaskType, taskType)
                .orderByDesc(KbTask::getId)
                .last("limit 1"));
        if (task == null) {
            task = new KbTask();
            task.setTaskId(bizIdGenerator.taskId());
            task.setTaskType(taskType);
            task.setBizId(versionId);
            task.setRetryCount(0);
            task.setStatus(TaskStatus.RUNNING);
            task.setProgress(0);
            kbTaskMapper.insert(task);
            return task;
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(0);
        task.setFailReason(null);
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        kbTaskMapper.update(null, new LambdaUpdateWrapper<KbTask>()
                .set(KbTask::getStatus, TaskStatus.RUNNING.name())
                .set(KbTask::getProgress, 0)
                .set(KbTask::getFailReason, null)
                .set(KbTask::getRetryCount, task.getRetryCount())
                .eq(KbTask::getTaskId, task.getTaskId()));
        return task;
    }

    /**
     * Records how far the build has got.
     *
     * @param task     running task, kept in sync in memory
     * @param progress percentage reached
     */
    public void progress(KbTask task, int progress) {
        task.setProgress(progress);
        kbTaskMapper.updateById(task);
    }

    /**
     * Closes the task as successful and clears the consecutive failure streak of its type.
     *
     * @param task running task, kept in sync in memory
     */
    public void complete(KbTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(PROGRESS_DONE);
        kbTaskMapper.updateById(task);
        taskFailureTracker.recordSuccess(task.getTaskType());
    }

    /**
     * Closes the task as failed and feeds the alerting streak of its type.
     *
     * @param task       running task, kept in sync in memory
     * @param safeReason classified failure cause, already truncated by {@link #truncateReason}
     */
    public void fail(KbTask task, String safeReason) {
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason(safeReason);
        kbTaskMapper.updateById(task);
        taskFailureTracker.recordFailure(task.getTaskType(), safeReason);
    }
}
