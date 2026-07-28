package io.kbrag.app.alert;

import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.AlertType;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.model.AlertConfig;
import io.kbrag.domain.port.WebhookNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the silence window, the three thresholds and the degradation of an unreachable channel.
 *
 * @author owlzhangfq@gmail.com
 */
class AlertServiceTest {

    private static final String WEBHOOK_URL = "https://example.invalid/hook";

    private AlertConfigService alertConfigService;
    private WebhookNotifier webhookNotifier;
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertConfigService = mock(AlertConfigService.class);
        webhookNotifier = mock(WebhookNotifier.class);
        alertService = new AlertService(alertConfigService, webhookNotifier);
        when(alertConfigService.current()).thenReturn(config(true, WEBHOOK_URL, 30));
    }

    @Test
    void shouldDeliverTheFirstAlertOfACategory() {
        assertTrue(alertService.raise(AlertType.TASK_FAILURE, "three failures in a row"));

        verify(webhookNotifier).notify(eq(WEBHOOK_URL), anyString());
    }

    @Test
    void shouldSuppressASecondAlertOfTheSameCategoryInsideTheSilenceWindow() {
        assertTrue(alertService.raise(AlertType.TASK_FAILURE, "first"));
        assertFalse(alertService.raise(AlertType.TASK_FAILURE, "second"));

        // The condition persists, so without a cooldown the channel meant to carry the warning becomes the
        // reason nobody reads it.
        verify(webhookNotifier, times(1)).notify(anyString(), anyString());
    }

    @Test
    void shouldSilenceEachCategoryIndependently() {
        assertTrue(alertService.raise(AlertType.TASK_FAILURE, "tasks failing"));
        assertTrue(alertService.raise(AlertType.SYNC_BACKLOG, "backlog growing"));

        // A task failure storm must not mute the unrelated backlog notification.
        verify(webhookNotifier, times(2)).notify(anyString(), anyString());
    }

    @Test
    void shouldSendAgainOnceTheSilenceWindowIsDisabled() {
        when(alertConfigService.current()).thenReturn(config(true, WEBHOOK_URL, 0));

        assertTrue(alertService.raise(AlertType.TASK_FAILURE, "first"));
        assertTrue(alertService.raise(AlertType.TASK_FAILURE, "second"));

        verify(webhookNotifier, times(2)).notify(anyString(), anyString());
    }

    @Test
    void shouldReportACategoryAsSilencedOnlyInsideItsWindow() {
        AlertConfig config = config(true, WEBHOOK_URL, 30);
        Instant sentAt = Instant.now();
        alertService.raise(AlertType.TASK_FAILURE, "first");

        assertTrue(alertService.isSilenced(AlertType.TASK_FAILURE, config, sentAt.plusSeconds(60)));
        assertFalse(alertService.isSilenced(AlertType.TASK_FAILURE, config, sentAt.plusSeconds(31 * 60)));
        assertFalse(alertService.isSilenced(AlertType.SYNC_BACKLOG, config, sentAt.plusSeconds(60)));
    }

    @Test
    void shouldDegradeToALogWithoutAWebhookUrl() {
        when(alertConfigService.current()).thenReturn(config(true, "", 30));

        assertFalse(alertService.raise(AlertType.SYNC_BACKLOG, "backlog growing"));

        // A deployment without a chat platform is supported: the alert must not raise a second problem.
        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldDegradeToALogWhileTheDispatcherIsDisabled() {
        when(alertConfigService.current()).thenReturn(config(false, WEBHOOK_URL, 30));

        assertFalse(alertService.raise(AlertType.TASK_FAILURE, "tasks failing"));

        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldSwallowATransportFailure() {
        doThrow(new IllegalStateException("connection refused"))
                .when(webhookNotifier).notify(anyString(), anyString());

        assertFalse(alertService.raise(AlertType.TASK_FAILURE, "tasks failing"));
    }

    @Test
    void shouldNotStartTheSilenceWindowAfterAFailedDelivery() {
        doThrow(new IllegalStateException("connection refused"))
                .when(webhookNotifier).notify(anyString(), anyString());
        assertFalse(alertService.raise(AlertType.TASK_FAILURE, "first"));

        // The message never arrived, so the next attempt has to be allowed through.
        assertFalse(alertService.isSilenced(AlertType.TASK_FAILURE, config(true, WEBHOOK_URL, 30),
                Instant.now()));
    }

    @Test
    void shouldSendTheManualProbeEvenWhileTheDispatcherIsDisabled() {
        when(alertConfigService.current()).thenReturn(config(false, WEBHOOK_URL, 30));

        // An operator verifies the wiring before enabling the feature; refusing would invert that order.
        assertTrue(alertService.sendTest("probe"));
        verify(webhookNotifier).notify(eq(WEBHOOK_URL), anyString());
    }

    @Test
    void shouldFailFastOnTheManualProbeWithoutAWebhookUrl() {
        when(alertConfigService.current()).thenReturn(config(true, "", 30));

        // A real alert degrades silently, but a human asking "does this work" must get a straight answer.
        BizException failure = assertThrows(BizException.class, () -> alertService.sendTest("probe"));
        assertTrue(failure.getMessage().contains("webhook"));
        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldIgnoreTheSilenceWindowForTheManualProbe() {
        assertTrue(alertService.sendTest("first probe"));
        assertTrue(alertService.sendTest("second probe"));

        verify(webhookNotifier, times(2)).notify(anyString(), anyString());
    }

    @Test
    void shouldRaiseTaskFailuresOnlyOnceTheThresholdIsReached() {
        TaskFailureTracker tracker = new TaskFailureTracker(alertConfigService, alertService,
                new KbMetrics(new SimpleMeterRegistry()));

        tracker.recordFailure(TaskType.INDEX, "engine unreachable");
        tracker.recordFailure(TaskType.INDEX, "engine unreachable");
        verify(webhookNotifier, never()).notify(anyString(), anyString());

        tracker.recordFailure(TaskType.INDEX, "engine unreachable");
        verify(webhookNotifier, times(1)).notify(anyString(), anyString());
        assertEquals(3, tracker.consecutiveFailures(TaskType.INDEX));
    }

    @Test
    void shouldResetTheFailureRunOnASuccess() {
        TaskFailureTracker tracker = new TaskFailureTracker(alertConfigService, alertService,
                new KbMetrics(new SimpleMeterRegistry()));

        tracker.recordFailure(TaskType.INDEX, "transient");
        tracker.recordFailure(TaskType.INDEX, "transient");
        tracker.recordSuccess(TaskType.INDEX);
        tracker.recordFailure(TaskType.INDEX, "transient");

        // A lifetime counter would eventually cross any threshold and alert about nothing.
        assertEquals(1, tracker.consecutiveFailures(TaskType.INDEX));
        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldCountFailureRunsPerTaskType() {
        TaskFailureTracker tracker = new TaskFailureTracker(alertConfigService, alertService,
                new KbMetrics(new SimpleMeterRegistry()));

        tracker.recordFailure(TaskType.INDEX, "one");
        tracker.recordFailure(TaskType.INDEX, "two");
        tracker.recordFailure(TaskType.REBUILD, "one");

        assertEquals(2, tracker.consecutiveFailures(TaskType.INDEX));
        assertEquals(1, tracker.consecutiveFailures(TaskType.REBUILD));
        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldRaiseTheDegradationAlertOnlyAboveTheThresholdAndTheSampleFloor() {
        KbProperties properties = new KbProperties();
        properties.getAlert().setDegradeMinSamples(4);
        RetrievalDegradeMonitor monitor = new RetrievalDegradeMonitor(properties);
        AlertEvaluator evaluator = new AlertEvaluator(alertConfigService, alertService, monitor,
                mock(ChunkIndexSyncMapper.class), properties);
        AlertConfig config = config(true, WEBHOOK_URL, 30);

        monitor.record(true);
        monitor.record(true);
        // Two of two is a hundred percent but tells nothing, so the sample floor holds the alert back.
        assertFalse(evaluator.evaluateDegradeRate(config));

        monitor.record(true);
        monitor.record(false);
        // Four samples, three degraded: 0.75 is above the 0.3 threshold.
        assertTrue(evaluator.evaluateDegradeRate(config));
    }

    @Test
    void shouldNotRaiseTheDegradationAlertBelowTheThreshold() {
        KbProperties properties = new KbProperties();
        properties.getAlert().setDegradeMinSamples(4);
        RetrievalDegradeMonitor monitor = new RetrievalDegradeMonitor(properties);
        AlertEvaluator evaluator = new AlertEvaluator(alertConfigService, alertService, monitor,
                mock(ChunkIndexSyncMapper.class), properties);

        monitor.record(true);
        monitor.record(false);
        monitor.record(false);
        monitor.record(false);
        monitor.record(false);

        // One of five is 0.2, below the 0.3 threshold.
        assertFalse(evaluator.evaluateDegradeRate(config(true, WEBHOOK_URL, 30)));
        verify(webhookNotifier, never()).notify(anyString(), anyString());
    }

    @Test
    void shouldRaiseTheBacklogAlertOnlyAboveTheThreshold() {
        KbProperties properties = new KbProperties();
        ChunkIndexSyncMapper syncMapper = mock(ChunkIndexSyncMapper.class);
        AlertEvaluator evaluator = new AlertEvaluator(alertConfigService, alertService,
                new RetrievalDegradeMonitor(properties), syncMapper, properties);
        AlertConfig config = config(true, WEBHOOK_URL, 30);

        when(syncMapper.selectCount(any())).thenReturn(1000L);
        assertFalse(evaluator.evaluateSyncBacklog(config), "equal to the threshold is not above it");

        when(syncMapper.selectCount(any())).thenReturn(1001L);
        assertTrue(evaluator.evaluateSyncBacklog(config));
    }

    @Test
    void shouldStayIdleWhileTheDispatcherIsDisabled() {
        KbProperties properties = new KbProperties();
        ChunkIndexSyncMapper syncMapper = mock(ChunkIndexSyncMapper.class);
        when(alertConfigService.current()).thenReturn(config(false, WEBHOOK_URL, 30));
        AlertEvaluator evaluator = new AlertEvaluator(alertConfigService, alertService,
                new RetrievalDegradeMonitor(properties), syncMapper, properties);

        evaluator.evaluate();

        verify(syncMapper, never()).selectCount(any());
    }

    @Test
    void shouldSwallowFailuresOfTheScheduledEntryPoint() {
        KbProperties properties = new KbProperties();
        ChunkIndexSyncMapper syncMapper = mock(ChunkIndexSyncMapper.class);
        when(syncMapper.selectCount(any())).thenThrow(new IllegalStateException("database down"));
        AlertEvaluator evaluator = new AlertEvaluator(alertConfigService, alertService,
                new RetrievalDegradeMonitor(properties), syncMapper, properties);

        // A scheduled method that throws stops being scheduled in some containers.
        evaluator.evaluate();
        assertTrue(true);
    }

    @Test
    void shouldReportAnEmptyDegradationWindowAsHealthy() {
        RetrievalDegradeMonitor monitor = new RetrievalDegradeMonitor(new KbProperties());

        RetrievalDegradeMonitor.Snapshot snapshot = monitor.snapshot();

        assertEquals(0, snapshot.total());
        assertEquals(0d, snapshot.ratio());
    }

    private AlertConfig config(boolean enabled, String webhookUrl, int silenceMinutes) {
        AlertConfig config = new AlertConfig();
        config.setEnabled(enabled);
        config.setWebhookUrl(webhookUrl);
        config.setSilenceMinutes(silenceMinutes);
        return config;
    }
}
