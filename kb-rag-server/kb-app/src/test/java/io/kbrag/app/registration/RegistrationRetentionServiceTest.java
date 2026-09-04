package io.kbrag.app.registration;

import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 固化注册临时状态的截止时间、有界吞吐和毒行隔离。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.of(2026, 8, 31, 8, 0);

    private final EmailVerificationMapper verificationMapper = mock(EmailVerificationMapper.class);
    private final RegistrationApplicationMapper applicationMapper =
            mock(RegistrationApplicationMapper.class);
    private final RegistrationApplicationExpiryService expiryService =
            mock(RegistrationApplicationExpiryService.class);
    private final RegistrationProperties properties = new RegistrationProperties();
    private final RegistrationRetentionService service = new RegistrationRetentionService(
            verificationMapper, applicationMapper, expiryService, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldUseKeysetBatchesAndContinueAfterAPoisonApplication() {
        RegistrationProperties.Cleanup cleanup = properties.getCleanup();
        cleanup.setBatchSize(2);
        cleanup.setMaxBatchesPerRun(3);
        LocalDateTime applicationCutoff = LocalDateTime.of(2026, 8, 1, 8, 0);
        RegistrationApplication first = candidate(1L, "reg_1");
        RegistrationApplication second = candidate(2L, "reg_2");
        RegistrationApplication third = candidate(3L, "reg_3");
        when(applicationMapper.selectExpiredPendingBatch(applicationCutoff, 0L, 2))
                .thenReturn(List.of(first, second));
        when(applicationMapper.selectExpiredPendingBatch(applicationCutoff, 2L, 2))
                .thenReturn(List.of(third));
        doThrow(new IllegalStateException("poison"))
                .when(expiryService).expireOne("reg_1", applicationCutoff, LOCAL_NOW);
        when(expiryService.expireOne("reg_2", applicationCutoff, LOCAL_NOW)).thenReturn(true);
        when(expiryService.expireOne("reg_3", applicationCutoff, LOCAL_NOW)).thenReturn(true);
        when(verificationMapper.deleteExpiredBatch(
                LocalDateTime.of(2026, 8, 30, 8, 0),
                LocalDateTime.of(2026, 8, 30, 8, 0),
                LOCAL_NOW,
                LocalDateTime.of(2026, 8, 24, 8, 0), 2))
                .thenReturn(2, 0);

        service.cleanupExpiredState();

        verify(applicationMapper).selectExpiredPendingBatch(applicationCutoff, 0L, 2);
        verify(applicationMapper).selectExpiredPendingBatch(applicationCutoff, 2L, 2);
        verify(expiryService).expireOne("reg_1", applicationCutoff, LOCAL_NOW);
        verify(expiryService).expireOne("reg_2", applicationCutoff, LOCAL_NOW);
        verify(expiryService).expireOne("reg_3", applicationCutoff, LOCAL_NOW);
        verify(verificationMapper, times(2)).deleteExpiredBatch(
                LocalDateTime.of(2026, 8, 30, 8, 0),
                LocalDateTime.of(2026, 8, 30, 8, 0),
                LOCAL_NOW,
                LocalDateTime.of(2026, 8, 24, 8, 0), 2);
    }

    @Test
    void shouldFailClosedWhenRetentionConfigurationIsInvalid() {
        properties.getCleanup().setPendingApplicationTtlDays(0);

        service.cleanupExpiredState();

        verifyNoInteractions(applicationMapper, verificationMapper, expiryService);
    }

    @Test
    void shouldKeepEnterpriseFriendlyDefaultsAndAllowCleanupToBeDisabled() {
        RegistrationProperties.Cleanup cleanup = properties.getCleanup();

        assertEquals(24, cleanup.getVerificationRetentionHours());
        assertEquals(7, cleanup.getTerminalVerificationRetentionDays());
        assertEquals(30, cleanup.getPendingApplicationTtlDays());
        assertEquals(200, cleanup.getBatchSize());
        assertEquals(50, cleanup.getMaxBatchesPerRun());
        cleanup.setEnabled(false);

        service.cleanupExpiredState();

        verifyNoInteractions(applicationMapper, verificationMapper, expiryService);
    }

    @Test
    void defaultCleanupAndOutboxCapacityMustExceedPublicSubmissionAdmission() {
        RegistrationProperties.Cleanup cleanup = properties.getCleanup();
        long cleanupCapacityPerRun = (long) cleanup.getBatchSize() * cleanup.getMaxBatchesPerRun();
        long admittedSubmissionsPerHour =
                (long) properties.getSubmitGlobalRateLimitPerMinute() * 60L;
        RegistrationProperties.Outbox outbox = properties.getOutbox();
        long outboxCapacityPerHour = 3_600_000L / outbox.getDispatchIntervalMs()
                * outbox.getBatchSize();

        assertTrue(cleanupCapacityPerRun > admittedSubmissionsPerHour);
        assertTrue(outboxCapacityPerHour >= cleanupCapacityPerRun);
    }

    private RegistrationApplication candidate(long id, String applicationId) {
        RegistrationApplication application = new RegistrationApplication();
        application.setId(id);
        application.setApplicationId(applicationId);
        return application;
    }
}
