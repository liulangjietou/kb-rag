package io.kbrag.app.registration;

import io.kbrag.domain.mapper.EmailVerificationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化 SMTP 交付确认、失败补偿的精确 CAS 与独立事务边界。
 *
 * @author owlzhangfq@gmail.com
 */
class EmailVerificationDeliveryServiceTest {

    @Test
    void shouldAdvanceOrCancelOnlyTheExactChallenge() {
        EmailVerificationMapper mapper = mock(EmailVerificationMapper.class);
        EmailVerificationDeliveryService service = new EmailVerificationDeliveryService(mapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 8, 0);
        when(mapper.markChallengeDelivered("evf_new", "new-hmac", now)).thenReturn(1);
        when(mapper.cancelIssuedChallenge("evf_old", "old-hmac", now)).thenReturn(0);

        assertEquals(1, service.complete("evf_new", "new-hmac", now));
        assertEquals(0, service.cancel("evf_old", "old-hmac", now));

        verify(mapper).markChallengeDelivered("evf_new", "new-hmac", now);
        verify(mapper).cancelIssuedChallenge("evf_old", "old-hmac", now);
    }

    @Test
    void issuanceAndDeliveryMustBeSeparateFromTheNonTransactionalMailBoundary()
            throws Exception {
        Method outer = EmailVerificationService.class.getMethod(
                "requestCode", String.class, String.class);
        Method issuance = EmailVerificationIssuanceService.class.getMethod(
                "prepare", io.kbrag.domain.entity.EmailVerification.class,
                LocalDateTime.class);
        Method complete = EmailVerificationDeliveryService.class.getMethod(
                "complete", String.class, String.class, LocalDateTime.class);
        Method cancel = EmailVerificationDeliveryService.class.getMethod(
                "cancel", String.class, String.class, LocalDateTime.class);

        assertNull(outer.getAnnotation(Transactional.class));
        assertEquals(Propagation.REQUIRES_NEW,
                issuance.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.REQUIRES_NEW,
                complete.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.REQUIRES_NEW,
                cancel.getAnnotation(Transactional.class).propagation());
    }
}
