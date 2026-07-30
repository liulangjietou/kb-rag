package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Drops the physical indices a knowledge base leaves behind, the M16 contract section 4.1.
 *
 * <p><b>Why a task instead of an inline drop.</b> Deleting an index is irreversible while every MySQL
 * statement of the surrounding deletion is not, so the drop must wait for the commit - the same
 * argument as {@link EngineChunkCleaner#removeAfterCommit}. And unlike a chunk removal, a failed drop
 * has no retrieval self healing to fall back on: nothing ever queries a deleted base again, so the
 * only thing that would notice a surviving index is the disk bill. The {@code CLEANUP} task row is
 * what keeps the failure visible and retryable.
 *
 * <p><b>The registry status is the work queue.</b> Every row of the base is marked
 * {@code PENDING_CLEANUP} inside the deleting transaction, and the runner drops whatever carries that
 * mark. This deliberately also collects the rows the snapshot retirement of M7 has been marking
 * without ever dropping: same status, same meaning, one collector.
 *
 * <p><b>Idempotent per row.</b> A name whose index is already gone counts as cleaned, which is what
 * makes a retry after a half failed run converge instead of failing forever on the successes of the
 * previous attempt. Aliases die with their index in both engines, so dropping the physical name is
 * the whole job.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class IndexCleanupService {

    private static final int PROGRESS_START = 0;
    private static final int PROGRESS_DONE = 100;
    private static final int FAIL_REASON_MAX_LENGTH = 1024;

    private final IndexRegistryMapper indexRegistryMapper;
    private final KbTaskMapper kbTaskMapper;
    private final BizIdGenerator bizIdGenerator;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final KbProperties properties;
    private final Executor indexExecutor;

    public IndexCleanupService(IndexRegistryMapper indexRegistryMapper,
                               KbTaskMapper kbTaskMapper,
                               BizIdGenerator bizIdGenerator,
                               FulltextStore fulltextStore,
                               VectorStore vectorStore,
                               KbProperties properties,
                               @Qualifier(AsyncConfig.INDEX_EXECUTOR) Executor indexExecutor) {
        this.indexRegistryMapper = indexRegistryMapper;
        this.kbTaskMapper = kbTaskMapper;
        this.bizIdGenerator = bizIdGenerator;
        this.fulltextStore = fulltextStore;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.indexExecutor = indexExecutor;
    }

    /**
     * Schedules the physical cleanup of a knowledge base being deleted.
     *
     * <p>Called inside the deleting transaction: the registry marks and the task row commit or roll
     * back together with the deletion, and the actual engine calls only start after the commit. A
     * rollback therefore leaves no task behind, and a crash between commit and execution leaves a
     * PENDING task the retry scan picks up.
     *
     * @param kbId knowledge base business id
     */
    public void submit(String kbId) {
        int marked = indexRegistryMapper.update(null, new LambdaUpdateWrapper<IndexRegistry>()
                .set(IndexRegistry::getStatus, IndexRegistryStatus.PENDING_CLEANUP)
                .eq(IndexRegistry::getKbId, kbId));
        openTask(kbId);
        log.info("index cleanup scheduled, kbId={}, markedIndices={}", kbId, marked);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submitAsync(kbId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitAsync(kbId);
            }
        });
    }

    /**
     * Same as {@link #run(String)} but off the caller thread - the deleting user is waiting for "the
     * base is gone", not for "the disk is back". Hand rolled over {@code @Async} because the submit
     * path calls it from inside this very bean, where a proxied annotation would silently run inline.
     *
     * @param kbId knowledge base business id
     */
    private void submitAsync(String kbId) {
        indexExecutor.execute(() -> {
            try {
                run(kbId);
            } catch (Exception e) {
                log.error("async index cleanup failed, errorCode={}, kbId={}",
                        ErrorCode.INTERNAL_ERROR, kbId, e);
            }
        });
    }

    /**
     * Drops every index of a knowledge base still marked for cleanup.
     *
     * <p>Best effort per row: a name the engine refuses to drop is logged and the remaining ones are
     * still attempted, so one unreachable engine does not protect the indices of the other. The rows
     * that failed keep their mark and the task ends FAILED, which is what hands them to the retry
     * scan.
     *
     * @param kbId knowledge base business id
     */
    public void run(String kbId) {
        KbTask task = startTask(kbId);
        List<IndexRegistry> rows = indexRegistryMapper.selectList(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getKbId, kbId)
                .eq(IndexRegistry::getStatus, IndexRegistryStatus.PENDING_CLEANUP));
        if (CollectionUtils.isEmpty(rows)) {
            completeTask(task);
            log.info("index cleanup found nothing to drop, kbId={}", kbId);
            return;
        }
        List<String> failed = new ArrayList<>();
        for (IndexRegistry row : rows) {
            try {
                dropPhysical(row);
                indexRegistryMapper.deleteById(row.getId());
            } catch (Exception e) {
                failed.add(row.getPhysicalIndexName());
                log.error("physical index could not be dropped, errorCode={}, kbId={}, index={}",
                        ErrorCode.INTERNAL_ERROR, kbId, row.getPhysicalIndexName(), e);
            }
        }
        if (failed.isEmpty()) {
            completeTask(task);
            log.info("index cleanup finished, kbId={}, droppedIndices={}", kbId, rows.size());
            return;
        }
        failTask(task, "indices left behind: " + String.join(", ", failed));
        log.error("index cleanup left indices behind, errorCode={}, kbId={}, failed={}",
                ErrorCode.INTERNAL_ERROR, kbId, failed);
    }

    /**
     * Picks up the cleanup tasks a crash or an engine outage left unfinished.
     *
     * <p>Rides the compensation switch and interval of the index synchronization scan: both exist to
     * convert persisted evidence of an unfinished engine write back into the write, and a deployment
     * that disables one wants the other quiet too. A task past the retry budget is reported once per
     * scan through the error log and otherwise left alone - the registry rows still name exactly what
     * survived, so an operator can finish the job by hand.
     */
    @Scheduled(fixedDelayString = "${kb.sync.compensation-interval-ms:30000}")
    public void retryScan() {
        if (!properties.getSync().isCompensationEnabled()) {
            return;
        }
        List<KbTask> tasks = kbTaskMapper.selectList(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getTaskType, TaskType.CLEANUP)
                .in(KbTask::getStatus, TaskStatus.PENDING, TaskStatus.FAILED));
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        int maxRetry = properties.getSync().getMaxRetry();
        for (KbTask task : tasks) {
            int retries = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retries >= maxRetry) {
                log.error("index cleanup abandoned after retries, errorCode={}, kbId={}, retries={}",
                        ErrorCode.INTERNAL_ERROR, task.getBizId(), retries);
                continue;
            }
            run(task.getBizId());
        }
    }

    /**
     * Drops the physical index of one registry row on whichever engine owns it.
     *
     * <p>A missing index is a success, not an error: the snapshot retirement path already dropped its
     * indices before marking the rows, and a retry must not fail on what the previous attempt already
     * achieved.
     *
     * @param row registry row marked for cleanup
     */
    private void dropPhysical(IndexRegistry row) {
        boolean esEngine = VectorEngine.from(row.getEngine()) == VectorEngine.ES;
        String name = row.getPhysicalIndexName();
        boolean exists = esEngine ? fulltextStore.indexExists(name) : vectorStore.indexExists(name);
        if (!exists) {
            log.info("physical index already gone, treated as cleaned, index={}", name);
            return;
        }
        if (esEngine) {
            fulltextStore.dropIndex(name);
        } else {
            vectorStore.dropIndex(name);
        }
    }

    /**
     * Opens or reuses the cleanup task of a knowledge base, one row per base like the graph
     * extraction: every retry is the same activity seen again, not a new thing to monitor.
     *
     * @param kbId knowledge base business id
     * @return task row in PENDING state
     */
    private KbTask openTask(String kbId) {
        KbTask task = findTask(kbId);
        if (task == null) {
            task = new KbTask();
            task.setTaskId(bizIdGenerator.taskId());
            task.setTaskType(TaskType.CLEANUP);
            task.setBizId(kbId);
            task.setRetryCount(0);
            task.setStatus(TaskStatus.PENDING);
            task.setProgress(PROGRESS_START);
            kbTaskMapper.insert(task);
            return task;
        }
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(PROGRESS_START);
        task.setFailReason(null);
        kbTaskMapper.updateById(task);
        return task;
    }

    private KbTask findTask(String kbId) {
        return kbTaskMapper.selectOne(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getBizId, kbId)
                .eq(KbTask::getTaskType, TaskType.CLEANUP)
                .orderByDesc(KbTask::getId)
                .last("limit 1"));
    }

    /**
     * Marks the task running, counting every start after the first as a retry.
     *
     * @param kbId knowledge base business id
     * @return task row marked running
     */
    private KbTask startTask(String kbId) {
        KbTask task = findTask(kbId);
        if (task == null) {
            // A direct run without a prior submit, the retry scan cannot produce this but a test can.
            task = openTask(kbId);
        }
        boolean firstRun = task.getStatus() == TaskStatus.PENDING
                && (task.getRetryCount() == null || task.getRetryCount() == 0);
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(PROGRESS_START);
        task.setFailReason(null);
        if (!firstRun) {
            task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        }
        kbTaskMapper.updateById(task);
        return task;
    }

    private void completeTask(KbTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(PROGRESS_DONE);
        kbTaskMapper.updateById(task);
    }

    private void failTask(KbTask task, String reason) {
        String safeReason = reason == null || reason.length() <= FAIL_REASON_MAX_LENGTH
                ? reason : reason.substring(0, FAIL_REASON_MAX_LENGTH);
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason(safeReason);
        kbTaskMapper.updateById(task);
    }
}
