package io.kbrag.app.registration;

import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.port.NotificationMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化 outbox 的短事务 lease、事务外 SMTP 与短事务完成边界。
 *
 * @author owlzhangfq@gmail.com
 */
class MailOutboxDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.of(2026, 8, 31, 8, 0);

    @Test
    void smtpMustRunOutsideTheLeaseAndCompletionTransactions() throws Exception {
        Method outer = MailOutboxDeliveryService.class.getMethod("deliverOne");
        Method claim = MailOutboxLeaseService.class.getMethod("claimNext", LocalDateTime.class);
        Method sent = MailOutboxCompletionService.class.getMethod(
                "markSent", String.class, LocalDateTime.class, int.class);
        Method failed = MailOutboxCompletionService.class.getMethod(
                "markFailed", String.class, int.class, LocalDateTime.class,
                String.class, int.class);

        assertNull(outer.getAnnotation(Transactional.class));
        assertEquals(Propagation.REQUIRES_NEW,
                claim.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.REQUIRES_NEW,
                sent.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.REQUIRES_NEW,
                failed.getAnnotation(Transactional.class).propagation());
    }

    @Test
    void shouldCompleteFirstAsSentAndSecondAsFailedWithoutAnSmtpTransaction() {
        MailOutboxLeaseService leaseService = mock(MailOutboxLeaseService.class);
        MailOutboxCompletionService completionService = mock(MailOutboxCompletionService.class);
        NotificationMailSender sender = mock(NotificationMailSender.class);
        RegistrationProperties properties = new RegistrationProperties();
        properties.getOutbox().setMaxRetries(5);
        properties.getOutbox().setRetryDelaySeconds(60);
        MailOutbox first = task("mail_1", "first@example.com", 0, 3);
        MailOutbox second = task("mail_2", "second@example.com", 0, 8);
        when(leaseService.claimNext(LOCAL_NOW))
                .thenReturn(Optional.of(first), Optional.of(second));
        AtomicInteger smtpCalls = new AtomicInteger();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (smtpCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("smtp unavailable");
            }
            return null;
        }).when(sender).send(any(), any(), any());
        MailOutboxDeliveryService service = new MailOutboxDeliveryService(
                leaseService, completionService, sender, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(service.deliverOne());
        assertTrue(service.deliverOne());

        verify(completionService).markSent("mail_1", LOCAL_NOW, 3);
        verify(completionService).markFailed("mail_2", 1, LOCAL_NOW.plusSeconds(60),
                "mail transport failed", 8);
        verify(sender, times(2)).send(any(), any(), any());
    }

    private MailOutbox task(String id, String recipient, int retries, int leaseVersion) {
        MailOutbox task = new MailOutbox();
        task.setOutboxId(id);
        task.setRecipient(recipient);
        task.setSubject("Registration review");
        task.setBody("Review completed");
        task.setRetryCount(retries);
        task.setLockVersion(leaseVersion);
        return task;
    }
}
