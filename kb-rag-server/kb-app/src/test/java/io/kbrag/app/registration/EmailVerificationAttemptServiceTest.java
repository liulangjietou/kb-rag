package io.kbrag.app.registration;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.VerificationCodeDeliveryStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化错误次数提交、绝对过期和成功后验证码摘要清除。
 *
 * @author owlzhangfq@gmail.com
 */
class EmailVerificationAttemptServiceTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String EMAIL = "person@example.com";
    private static final String CODE = "123456";

    private final EmailVerificationMapper mapper = mock(EmailVerificationMapper.class);
    private final RegistrationProperties properties = properties();
    private final EmailVerificationAttemptService service =
            new EmailVerificationAttemptService(mapper, new RegistrationHmac(properties));

    @BeforeEach
    void setUp() {
        when(mapper.updateById(any(EmailVerification.class))).thenReturn(1);
    }

    @Test
    void shouldCommitAnIncorrectAttemptAndInvalidateAtZero() throws Exception {
        EmailVerification verification = issued(1, LocalDateTime.now().plusMinutes(5));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, "000000", "ticket-hash", LocalDateTime.now().plusMinutes(15), LocalDateTime.now());

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INCORRECT, result);
        assertEquals(0, verification.getAttemptsRemaining());
        assertEquals(EmailVerificationStatus.INVALIDATED, verification.getStatus());
        assertNull(verification.getCodeHmac());
        verify(mapper).updateById(verification);

        EmailVerificationAttemptService.VerificationAttempt sixth = service.verify(
                EMAIL, "000000", "other-ticket", LocalDateTime.now().plusMinutes(15), LocalDateTime.now());
        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INVALID, sixth);
        assertEquals(0, verification.getAttemptsRemaining());
        verify(mapper, times(1)).updateById(verification);
    }

    @Test
    void shouldRejectAbsoluteExpiryAndClearCodeHmac() throws Exception {
        EmailVerification verification = issued(5, LocalDateTime.now().minusSeconds(1));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, CODE, "ticket-hash", LocalDateTime.now().plusMinutes(15), LocalDateTime.now());

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INVALID, result);
        assertEquals(EmailVerificationStatus.INVALIDATED, verification.getStatus());
        assertNull(verification.getCodeHmac());
    }

    @Test
    void shouldReplaceCodeHmacWithOneTimeTicketHashOnSuccess() throws Exception {
        EmailVerification verification = issued(5, LocalDateTime.now().plusMinutes(5));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);
        LocalDateTime ticketExpiry = LocalDateTime.now().plusMinutes(15);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, CODE, "ticket-hash", ticketExpiry, LocalDateTime.now());

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.VERIFIED, result);
        assertEquals(EmailVerificationStatus.VERIFIED, verification.getStatus());
        assertEquals(VerificationCodeDeliveryStatus.NONE,
                verification.getCodeDeliveryStatus());
        assertNull(verification.getCodeHmac());
        assertEquals(0, verification.getAttemptsRemaining());
        assertEquals("ticket-hash", verification.getTicketHash());
        assertEquals(ticketExpiry, verification.getTicketExpiresAt());
    }

    @Test
    void shouldRejectACodeUntilItsSmtpDeliveryIsConfirmed() throws Exception {
        EmailVerification verification = issued(5, LocalDateTime.now().plusMinutes(5));
        verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.ISSUING);
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, CODE, "ticket-hash", LocalDateTime.now().plusMinutes(15), LocalDateTime.now());

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INVALID, result);
        assertEquals(VerificationCodeDeliveryStatus.ISSUING,
                verification.getCodeDeliveryStatus());
        verify(mapper, times(0)).updateById(verification);
    }

    @Test
    void shouldKeepAnActiveTicketWhenANewChallengeExhaustsItsAttempts() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 8, 0);
        EmailVerification verification = verifiedWithChallenge(1, now.plusMinutes(5), now.plusMinutes(15));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, "000000", "replacement-ticket", now.plusMinutes(15), now);

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INCORRECT, result);
        assertEquals(EmailVerificationStatus.VERIFIED, verification.getStatus());
        assertNull(verification.getCodeHmac());
        assertEquals(0, verification.getAttemptsRemaining());
        assertEquals("active-ticket", verification.getTicketHash());
        assertEquals(now.plusMinutes(15), verification.getTicketExpiresAt());
    }

    @Test
    void shouldKeepAnActiveTicketWhenTheParallelChallengeExpires() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 8, 0);
        EmailVerification verification = verifiedWithChallenge(5, now.minusSeconds(1),
                now.plusMinutes(15));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, CODE, "replacement-ticket", now.plusMinutes(15), now);

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.INVALID, result);
        assertEquals(EmailVerificationStatus.VERIFIED, verification.getStatus());
        assertNull(verification.getCodeHmac());
        assertEquals(0, verification.getAttemptsRemaining());
        assertEquals("active-ticket", verification.getTicketHash());
    }

    @Test
    void shouldReplaceTheOldTicketOnlyAfterTheNewChallengeSucceeds() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 8, 0);
        EmailVerification verification = verifiedWithChallenge(5, now.plusMinutes(5),
                now.plusMinutes(10));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);

        EmailVerificationAttemptService.VerificationAttempt result = service.verify(
                EMAIL, CODE, "replacement-ticket", now.plusMinutes(20), now);

        assertEquals(EmailVerificationAttemptService.VerificationAttempt.VERIFIED, result);
        assertEquals(EmailVerificationStatus.VERIFIED, verification.getStatus());
        assertNull(verification.getCodeHmac());
        assertEquals(0, verification.getAttemptsRemaining());
        assertEquals("replacement-ticket", verification.getTicketHash());
        assertEquals(now.plusMinutes(20), verification.getTicketExpiresAt());
        assertEquals(now, verification.getVerifiedAt());
    }

    @Test
    void shouldFailClosedWhenTheVerifiedTicketCannotBePersisted() throws Exception {
        EmailVerification verification = issued(5, LocalDateTime.now().plusMinutes(5));
        when(mapper.selectByEmailForUpdate(EMAIL)).thenReturn(verification);
        when(mapper.updateById(verification)).thenReturn(0);

        assertThrows(BizException.class, () -> service.verify(
                EMAIL, CODE, "ticket-hash", LocalDateTime.now().plusMinutes(15), LocalDateTime.now()));
    }

    private EmailVerification issued(int remaining, LocalDateTime expiresAt) throws Exception {
        EmailVerification verification = new EmailVerification();
        verification.setVerificationId("evf_1");
        verification.setEmail(EMAIL);
        verification.setCodeHmac(hmac("evf_1", EMAIL, CODE));
        verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.DELIVERED);
        verification.setStatus(EmailVerificationStatus.ISSUED);
        verification.setAttemptsRemaining(remaining);
        verification.setExpiresAt(expiresAt);
        return verification;
    }

    private EmailVerification verifiedWithChallenge(int remaining, LocalDateTime codeExpiresAt,
                                                    LocalDateTime ticketExpiresAt) throws Exception {
        EmailVerification verification = issued(remaining, codeExpiresAt);
        verification.setStatus(EmailVerificationStatus.VERIFIED);
        verification.setTicketHash("active-ticket");
        verification.setTicketExpiresAt(ticketExpiresAt);
        verification.setVerifiedAt(ticketExpiresAt.minusMinutes(15));
        return verification;
    }

    private String hmac(String verificationId, String email, String code) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (verificationId + "\n" + email + "\n" + code).getBytes(StandardCharsets.UTF_8)));
    }

    private RegistrationProperties properties() {
        RegistrationProperties value = new RegistrationProperties();
        value.setCodeHmacKey(KEY);
        return value;
    }
}
