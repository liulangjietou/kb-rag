package io.kbrag.app.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.enums.AlertType;
import io.kbrag.domain.enums.IndexSyncStatus;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.model.AlertConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically evaluates the two alert triggers that are conditions rather than events.
 *
 * <p>The retrieval degradation ratio and the double write backlog cannot be raised from the code path
 * that produces them: a single degraded search is not an incident, and the backlog is only meaningful as
 * a total. Both are therefore sampled on a timer, which also means the alert cannot slow down a search.
 *
 * <p>The degradation ratio is ignored below a minimum sample count. Two searches of which one degraded is
 * a fifty percent ratio and tells nothing; requiring a floor is what keeps the first query after a restart
 * from paging an operator.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluator {

    private static final String DEGRADE_MESSAGE_TEMPLATE =
            "retrieval degradation ratio %.2f over the last %d minute(s) exceeds the threshold %.2f "
                    + "(%d of %d calls)";
    private static final String BACKLOG_MESSAGE_TEMPLATE =
            "index synchronization backlog of %d chunk(s) exceeds the threshold %d";

    private final AlertConfigService alertConfigService;
    private final AlertService alertService;
    private final RetrievalDegradeMonitor retrievalDegradeMonitor;
    private final ChunkIndexSyncMapper chunkIndexSyncMapper;
    private final KbProperties properties;

    /**
     * Periodic entry point.
     *
     * <p>Failures are swallowed on purpose: a scheduled method that throws stops being scheduled in some
     * containers, and the evaluator going silent is worse than one missed pass.
     */
    @Scheduled(fixedDelayString = "${kb.alert.evaluation-interval-ms:60000}")
    public void evaluate() {
        try {
            AlertConfig config = alertConfigService.current();
            if (!config.isEnabled()) {
                return;
            }
            evaluateDegradeRate(config);
            evaluateSyncBacklog(config);
        } catch (Exception e) {
            log.error("alert evaluation failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Raises the retrieval degradation alert.
     *
     * @param config current settings
     * @return {@code true} when an alert was raised
     */
    boolean evaluateDegradeRate(AlertConfig config) {
        RetrievalDegradeMonitor.Snapshot snapshot = retrievalDegradeMonitor.snapshot();
        int minSamples = properties.getAlert().getDegradeMinSamples();
        if (snapshot.total() < minSamples || snapshot.ratio() <= config.getDegradeRateThreshold()) {
            return false;
        }
        return alertService.raise(AlertType.RETRIEVAL_DEGRADE, String.format(DEGRADE_MESSAGE_TEMPLATE,
                snapshot.ratio(), properties.getAlert().getDegradeWindowMinutes(),
                config.getDegradeRateThreshold(), snapshot.degraded(), snapshot.total()));
    }

    /**
     * Raises the double write backlog alert.
     *
     * @param config current settings
     * @return {@code true} when an alert was raised
     */
    boolean evaluateSyncBacklog(AlertConfig config) {
        long backlog = chunkIndexSyncMapper.selectCount(new LambdaQueryWrapper<ChunkIndexSync>()
                .in(ChunkIndexSync::getStatus, IndexSyncStatus.PENDING, IndexSyncStatus.FAILED));
        if (backlog <= config.getSyncBacklogThreshold()) {
            return false;
        }
        return alertService.raise(AlertType.SYNC_BACKLOG,
                String.format(BACKLOG_MESSAGE_TEMPLATE, backlog, config.getSyncBacklogThreshold()));
    }
}
