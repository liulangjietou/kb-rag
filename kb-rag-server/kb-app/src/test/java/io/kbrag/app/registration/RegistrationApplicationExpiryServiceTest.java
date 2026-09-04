package io.kbrag.app.registration;

import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.MailOutboxStatus;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化超期待审核申请的清密、条件迁移和同事务通知契约。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationApplicationExpiryServiceTest {

    private static final String APPLICATION_ID = "reg_1";
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 1, 8, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 8, 0);

    private final RegistrationApplicationMapper applicationMapper =
            mock(RegistrationApplicationMapper.class);
    private final MailOutboxMapper outboxMapper = mock(MailOutboxMapper.class);
    private final RegistrationApplicationExpiryService service =
            new RegistrationApplicationExpiryService(applicationMapper, outboxMapper);

    @Test
    void shouldConditionallyClosePendingApplicationAndCreateAnAccurateNotification() {
        RegistrationApplication application = pendingApplication(CUTOFF.minusSeconds(1));
        when(applicationMapper.selectByApplicationIdForUpdate(APPLICATION_ID))
                .thenReturn(application);
        when(applicationMapper.expirePendingIfEligible(APPLICATION_ID, CUTOFF, NOW,
                RegistrationApplicationExpiryService.SYSTEM_EXPIRY_REASON)).thenReturn(1);
        when(outboxMapper.insert(any(MailOutbox.class))).thenReturn(1);

        boolean expired = service.expireOne(APPLICATION_ID, CUTOFF, NOW);

        assertTrue(expired);
        verify(applicationMapper).expirePendingIfEligible(APPLICATION_ID, CUTOFF, NOW,
                RegistrationApplicationExpiryService.SYSTEM_EXPIRY_REASON);
        ArgumentCaptor<MailOutbox> outbox = ArgumentCaptor.forClass(MailOutbox.class);
        verify(outboxMapper).insert(outbox.capture());
        assertEquals("person@example.com", outbox.getValue().getRecipient());
        assertEquals(MailOutboxStatus.PENDING, outbox.getValue().getStatus());
        assertEquals(0, outbox.getValue().getRetryCount());
        assertEquals(NOW, outbox.getValue().getNextRetryAt());
        assertTrue(outbox.getValue().getSubject().contains("自动关闭"));
        assertTrue(outbox.getValue().getBody().contains("并非人工驳回"));
        assertTrue(outbox.getValue().getBody().contains("重新验证邮箱并提交申请"));
    }

    @Test
    void shouldNotCreateNotificationWhenConcurrentReviewWinsTheConditionalUpdate() {
        RegistrationApplication application = pendingApplication(CUTOFF.minusSeconds(1));
        when(applicationMapper.selectByApplicationIdForUpdate(APPLICATION_ID))
                .thenReturn(application);
        when(applicationMapper.expirePendingIfEligible(APPLICATION_ID, CUTOFF, NOW,
                RegistrationApplicationExpiryService.SYSTEM_EXPIRY_REASON)).thenReturn(0);

        boolean expired = service.expireOne(APPLICATION_ID, CUTOFF, NOW);

        assertFalse(expired);
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.any(MailOutbox.class));
    }

    @Test
    void shouldSkipApplicationsThatAreNoLongerPendingOrNoLongerExpired() {
        RegistrationApplication approved = pendingApplication(CUTOFF.minusDays(1));
        approved.setStatus(RegistrationApplicationStatus.APPROVED);
        when(applicationMapper.selectByApplicationIdForUpdate(APPLICATION_ID))
                .thenReturn(approved);

        assertFalse(service.expireOne(APPLICATION_ID, CUTOFF, NOW));

        verify(applicationMapper, never()).expirePendingIfEligible(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.any(MailOutbox.class));
    }

    @Test
    void eachApplicationMustUseAnIndependentRequiresNewTransaction() throws Exception {
        Method method = RegistrationApplicationExpiryService.class.getMethod(
                "expireOne", String.class, LocalDateTime.class, LocalDateTime.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void shouldFailTheExpiryTransactionWhenItsNotificationCannotBePersisted() {
        RegistrationApplication application = pendingApplication(CUTOFF.minusSeconds(1));
        when(applicationMapper.selectByApplicationIdForUpdate(APPLICATION_ID))
                .thenReturn(application);
        when(applicationMapper.expirePendingIfEligible(APPLICATION_ID, CUTOFF, NOW,
                RegistrationApplicationExpiryService.SYSTEM_EXPIRY_REASON)).thenReturn(1);
        when(outboxMapper.insert(any(MailOutbox.class))).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.expireOne(APPLICATION_ID, CUTOFF, NOW));
    }

    private RegistrationApplication pendingApplication(LocalDateTime createdAt) {
        RegistrationApplication application = new RegistrationApplication();
        application.setApplicationId(APPLICATION_ID);
        application.setEmail("person@example.com");
        application.setDisplayName("Alice");
        application.setPasswordHash("bcrypt-hash");
        application.setStatus(RegistrationApplicationStatus.PENDING);
        application.setCreatedAt(createdAt);
        return application;
    }
}
