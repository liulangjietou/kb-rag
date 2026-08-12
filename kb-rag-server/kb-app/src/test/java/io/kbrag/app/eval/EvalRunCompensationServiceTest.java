package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.mapper.EvalRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the sweep that closes out evaluation runs no thread is advancing any more: the rows a process
 * that died mid execution left behind, which no in-process handler can ever reach.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalRunCompensationServiceTest {

    private static final String STUCK_RUN = "evr_stuck";

    private EvalRunMapper evalRunMapper;
    private KbProperties properties;
    private EvalRunCompensationService service;

    @BeforeEach
    void setUp() {
        // The scan writes through a wrapper rather than through updateById, so the lambda column cache a
        // Spring context would have filled by scanning the mappers has to be filled by hand here.
        MybatisLambdaCache.register(EvalRun.class);
        evalRunMapper = mock(EvalRunMapper.class);
        properties = new KbProperties();
        service = new EvalRunCompensationService(evalRunMapper, properties);
    }

    @Test
    void shouldFailARunThatHasNotMovedSinceTheConfiguredBudget() {
        when(evalRunMapper.selectList(any())).thenReturn(List.of(stuckRun(RunStatus.RUNNING)));
        when(evalRunMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertEquals(1, service.compensate());

        ArgumentCaptor<LambdaUpdateWrapper<EvalRun>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(evalRunMapper).update(isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment() + captor.getValue().getSqlSet();
        assertTrue(sql.contains("run_id"), "the update must address exactly the run the scan picked up");
        assertTrue(sql.contains("status"), "the update must state the new status and guard the old one");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(STUCK_RUN));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(RunStatus.FAILED));
    }

    /**
     * The scan must not write through {@code updateById}.
     *
     * <p>Both halves of that matter. {@code updateById} bumps the optimistic lock version, so a run reaped
     * a little early - a deployment whose real runs outlast the configured budget - would lose the update
     * its own thread writes when it finishes, stranding the per case rows it had already produced under a
     * run marked failed. And it would write the whole entity back, including the columns the scan read
     * minutes ago and has no business restating.
     */
    @Test
    void shouldNotTouchTheOptimisticLockOfARunThatMayStillBeExecuting() {
        when(evalRunMapper.selectList(any())).thenReturn(List.of(stuckRun(RunStatus.RUNNING)));
        when(evalRunMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.compensate();

        verify(evalRunMapper, never()).updateById(any(EvalRun.class));
    }

    /**
     * The update must refuse a run that reached a terminal state between the select and the write.
     */
    @Test
    void shouldLeaveARunThatFinishedBetweenTheSelectAndTheUpdateAlone() {
        when(evalRunMapper.selectList(any())).thenReturn(List.of(stuckRun(RunStatus.PENDING)));
        // The status predicate matched nothing: the run's own thread got there first.
        when(evalRunMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertEquals(0, service.compensate());
    }

    @Test
    void shouldOnlyConsiderNonTerminalRunsOlderThanTheBudget() {
        properties.getEval().setStuckTimeoutMinutes(30);
        when(evalRunMapper.selectList(any())).thenReturn(List.of());

        service.compensate();

        ArgumentCaptor<LambdaQueryWrapper<EvalRun>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(evalRunMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "a terminal run must never be picked up");
        assertTrue(sql.contains("updated_at"), "a run that moved recently must never be picked up");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(RunStatus.PENDING));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(RunStatus.RUNNING));
    }

    @Test
    void shouldNotQueryAnythingWhenTheScanIsDisabled() {
        properties.getEval().setStuckScanEnabled(false);

        service.scan();

        verifyNoInteractions(evalRunMapper);
    }

    @Test
    void shouldSwallowAFailedScanSoTheScheduleKeepsRunning() {
        when(evalRunMapper.selectList(any())).thenThrow(new IllegalStateException("database down"));

        service.scan();

        verify(evalRunMapper).selectList(any());
    }

    private EvalRun stuckRun(RunStatus status) {
        EvalRun run = new EvalRun();
        run.setRunId(STUCK_RUN);
        run.setStatus(status);
        return run;
    }
}
