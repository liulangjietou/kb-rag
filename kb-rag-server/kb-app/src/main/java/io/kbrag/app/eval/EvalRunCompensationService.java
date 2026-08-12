package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.mapper.EvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes out the evaluation runs no thread is advancing any more.
 *
 * <p><b>Why a scan is needed at all.</b> A run row is inserted {@code PENDING} and only then handed to the
 * evaluation pool, so the two are not one atomic act: a process that dies between them - or during the
 * execution itself - leaves a row in {@code PENDING} or {@code RUNNING} that nothing on any thread will
 * ever touch again. Nothing else in the system notices. The console renders it as an evaluation still in
 * progress forever, and it is counted as backlog by every metric that asks how much work is outstanding.
 * The submission-time rejection {@code EvalRunService} records covers the one non-crash way a run can be
 * orphaned; this covers the rest, which are exactly the ones no in-process handler can.
 *
 * <p><b>It fails runs, it never restarts them.</b> {@code EvalRunService#execute} inserts one result row
 * per case without first clearing what a previous attempt left behind, so re-running an abandoned run
 * would double its per case rows and quietly corrupt every metric computed over them, including a release
 * gate's. Failing the run states what is true - it produced no trustworthy metrics - and re-submitting is
 * an operator's decision with a fresh run id.
 *
 * <p><b>The update is deliberately not an {@code updateById}.</b> A run reaped a little early - a
 * deployment whose real runs outlast the configured budget - is still executing, and its own thread will
 * write the true outcome when it finishes. A wrapper update leaves the optimistic lock version untouched,
 * so that write still lands; {@code updateById} would bump the version, silently lose the executing
 * thread's update, and strand the result rows it had already written under a run marked failed. The status
 * predicate in the where clause is the other half: a run that reached a terminal state between the select
 * and the update is left exactly as it is.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunCompensationService {

    /** Recorded on a run whose executing process never came back. */
    private static final String ABANDONED_REASON = "评测执行中断：该运行长时间没有任何状态推进，"
            + "判定为执行进程已退出，请重新提交评测";

    private final EvalRunMapper evalRunMapper;
    private final KbProperties properties;

    /**
     * Periodic entry point.
     */
    @Scheduled(fixedDelayString = "${kb.eval.stuck-scan-interval-ms:300000}")
    public void scan() {
        if (!properties.getEval().isStuckScanEnabled()) {
            return;
        }
        try {
            int abandoned = compensate();
            if (abandoned > 0) {
                log.info("abandoned evaluation runs closed out, runs={}", abandoned);
            }
        } catch (Exception e) {
            log.error("abandoned evaluation run scan failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Runs one compensation pass.
     *
     * @return number of runs moved out of a non terminal state
     */
    public int compensate() {
        KbProperties.Eval eval = properties.getEval();
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(eval.getStuckTimeoutMinutes());
        List<EvalRun> stuck = evalRunMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .in(EvalRun::getStatus, RunStatus.PENDING, RunStatus.RUNNING)
                .lt(EvalRun::getUpdatedAt, staleBefore)
                .orderByAsc(EvalRun::getId)
                .last("limit " + eval.getStuckScanBatchSize()));
        if (CollectionUtils.isEmpty(stuck)) {
            return 0;
        }
        int abandoned = 0;
        for (EvalRun run : stuck) {
            abandoned += abandon(run);
        }
        return abandoned;
    }

    /**
     * Moves one abandoned run to {@code FAILED}, unless it reached a terminal state in the meantime.
     *
     * @param run run picked up by the scan
     * @return 1 when the row was updated, 0 when another writer got there first
     */
    private int abandon(EvalRun run) {
        LocalDateTime now = LocalDateTime.now();
        int updated = evalRunMapper.update(null, new LambdaUpdateWrapper<EvalRun>()
                .set(EvalRun::getStatus, RunStatus.FAILED)
                .set(EvalRun::getFailReason, ABANDONED_REASON)
                .set(EvalRun::getFinishedAt, now)
                .set(EvalRun::getUpdatedAt, now)
                .eq(EvalRun::getRunId, run.getRunId())
                .in(EvalRun::getStatus, RunStatus.PENDING, RunStatus.RUNNING));
        if (updated > 0) {
            log.error("evaluation run abandoned by its executing process, errorCode={}, runId={}, lastStatus={}",
                    ErrorCode.INTERNAL_ERROR, run.getRunId(), run.getStatus());
        }
        return updated;
    }
}
