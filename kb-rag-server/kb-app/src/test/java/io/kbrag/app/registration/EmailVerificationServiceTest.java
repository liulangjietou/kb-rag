package io.kbrag.app.registration;

import io.kbrag.app.auth.EmailIdentityClaimService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.VerificationCodeDeliveryStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import io.kbrag.domain.mapper.RegistrationSubmissionClaimMapper;
import io.kbrag.domain.port.NotificationMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 固化验证码不落明文、单邮箱单行轮换以及已占用邮箱的同构公开响应。
 *
 * @author owlzhangfq@gmail.com
 */
class EmailVerificationServiceTest {

    private static final String EMAIL = "person@example.com";
    private static final String IP = "203.0.113.9";
    private static final String SUBMISSION_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String REPLACEMENT_SUBMISSION_ID = "223e4567-e89b-42d3-a456-426614174000";

    private final EmailVerificationMapper verificationMapper = mock(EmailVerificationMapper.class);
    private final EmailIdentityClaimService identityClaimService =
            mock(EmailIdentityClaimService.class);
    private final NotificationMailSender mailSender = mock(NotificationMailSender.class);
    private final RegistrationRateLimiter rateLimiter = mock(RegistrationRateLimiter.class);
    private final EmailVerificationAttemptService attemptService = mock(EmailVerificationAttemptService.class);
    private final SecureRandom secureRandom = mock(SecureRandom.class);
    private final RegistrationProperties properties = properties();
    private final AtomicReference<EmailVerification> stored = new AtomicReference<>();
    private final AtomicInteger ticketSequence = new AtomicInteger();
    private final EmailVerificationDeliveryService deliveryService =
            mock(EmailVerificationDeliveryService.class);
    private RegistrationHmac registrationHmac;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        when(mailSender.available()).thenReturn(true);
        when(secureRandom.nextInt(anyInt())).thenReturn(123456);
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            Arrays.fill(bytes, (byte) ticketSequence.incrementAndGet());
            return null;
        }).when(secureRandom).nextBytes(any(byte[].class));
        doAnswer(invocation -> {
            EmailVerification candidate = invocation.getArgument(0);
            // 故意在冲突时返回 1，模拟 CLIENT_FOUND_ROWS；业务不能信任 affected rows。
            if (stored.compareAndSet(null, candidate)) {
                candidate.setId(1L);
            }
            return 1;
        }).when(verificationMapper).insertIfAbsent(any(EmailVerification.class));
        when(verificationMapper.selectByEmailForUpdate(EMAIL)).thenAnswer(ignored -> stored.get());
        when(verificationMapper.updateById(any(EmailVerification.class))).thenReturn(1);
        when(deliveryService.complete(anyString(), anyString(), any())).thenAnswer(invocation -> {
            EmailVerification current = stored.get();
            String verificationId = invocation.getArgument(0);
            String codeHmac = invocation.getArgument(1);
            if (current != null && verificationId.equals(current.getVerificationId())
                    && codeHmac.equals(current.getCodeHmac())
                    && current.getCodeDeliveryStatus() == VerificationCodeDeliveryStatus.ISSUING) {
                current.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.DELIVERED);
                return 1;
            }
            return 0;
        });
        registrationHmac = new RegistrationHmac(properties);
        EmailVerificationIssuanceService issuanceService =
                new EmailVerificationIssuanceService(verificationMapper, identityClaimService);
        service = new EmailVerificationService(mailSender, properties, rateLimiter, attemptService,
                issuanceService, deliveryService, new RegistrationMailBulkhead(properties),
                registrationHmac, secureRandom,
                Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldPersistOnlyHmacAndKeepCoolingResponseIsomorphic() {
        VerificationCodeRequested first = service.requestCode(" Person@Example.com ", IP);
        EmailVerification verification = stored.get();

        assertEquals(60, first.resendAfterSeconds());
        assertNotNull(verification);
        assertEquals(64, verification.getCodeHmac().length());
        assertFalse(verification.getCodeHmac().contains("123456"));
        assertEquals(VerificationCodeDeliveryStatus.DELIVERED,
                verification.getCodeDeliveryStatus());
        assertEquals(64, verification.getRequestIpHash().length());

        VerificationCodeRequested second = service.requestCode(EMAIL, IP);

        assertEquals(first, second);
        verify(mailSender, times(2)).send(anyString(), anyString(), anyString());
        verify(verificationMapper, never()).updateById(any(EmailVerification.class));
    }

    @Test
    void shouldKeepOccupiedEmailInTheSameStateMachineWithoutDeliveringTheCode() {
        when(identityClaimService.claimed(EMAIL)).thenReturn(true);

        VerificationCodeRequested occupied = service.requestCode(EMAIL, IP);

        assertEquals(new VerificationCodeRequested(60), occupied);
        assertNotNull(stored.get());
        assertEquals(64, stored.get().getCodeHmac().length());
        verify(verificationMapper).insertIfAbsent(any(EmailVerification.class));
        verify(verificationMapper).selectByEmailForUpdate(EMAIL);
        verify(identityClaimService).claimed(EMAIL);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), eq("Knowledge Atlas 注册提示"), body.capture());
        assertFalse(body.getValue().contains("123456"));
    }

    @Test
    void shouldRotateTheSingleRowAfterCooldown() {
        service.requestCode(EMAIL, IP);
        EmailVerification first = stored.get();
        first.setResendAvailableAt(first.getResendAvailableAt().minusMinutes(2));

        service.requestCode(EMAIL, IP);

        verify(verificationMapper).updateById(first);
        assertEquals("123456", latestCodeFromMailBody());
    }

    @Test
    void shouldRotateAnInvalidatedRowEvenInsideTheResendWindow() {
        service.requestCode(EMAIL, IP);
        EmailVerification first = stored.get();
        first.setStatus(io.kbrag.domain.enums.EmailVerificationStatus.INVALIDATED);
        first.setCodeHmac(null);

        service.requestCode(EMAIL, IP);

        verify(verificationMapper).updateById(first);
        assertEquals(io.kbrag.domain.enums.EmailVerificationStatus.ISSUED, first.getStatus());
        assertNotNull(first.getCodeHmac());
        assertEquals("123456", latestCodeFromMailBody());
    }

    @Test
    void shouldFailClosedWithoutSendingWhenTheRotatedChallengeWasNotPersisted() {
        service.requestCode(EMAIL, IP);
        EmailVerification first = stored.get();
        first.setResendAvailableAt(first.getResendAvailableAt().minusMinutes(2));
        when(verificationMapper.updateById(first)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.requestCode(EMAIL, IP));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        verify(mailSender).send(anyString(), anyString(), anyString());
    }

    @Test
    void shouldPreserveAnActiveTicketWhenCodeIsRequestedAgainAndAllowSubmission() {
        Clock lifecycleClock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        EmailVerificationAttemptService realAttemptService =
                new EmailVerificationAttemptService(verificationMapper, registrationHmac);
        EmailVerificationIssuanceService issuanceService =
                new EmailVerificationIssuanceService(verificationMapper, identityClaimService);
        EmailVerificationService lifecycleService = new EmailVerificationService(
                mailSender, properties, rateLimiter, realAttemptService, issuanceService,
                deliveryService, new RegistrationMailBulkhead(properties), registrationHmac,
                secureRandom, lifecycleClock);
        lifecycleService.requestCode(EMAIL, IP);
        VerifiedEmailTicket ticket = lifecycleService.verify(EMAIL, "123456", IP);
        EmailVerification verified = stored.get();
        String ticketHash = verified.getTicketHash();

        VerificationCodeRequested repeated = lifecycleService.requestCode(EMAIL, IP);

        assertEquals(new VerificationCodeRequested(60), repeated);
        assertEquals(io.kbrag.domain.enums.EmailVerificationStatus.VERIFIED, verified.getStatus());
        assertEquals(ticketHash, verified.getTicketHash());
        ArgumentCaptor<String> mailBody = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(2)).send(eq(EMAIL), anyString(), mailBody.capture());
        assertEquals("123456", mailBody.getAllValues().get(1).replaceAll(".*?(\\d{6}).*", "$1"));

        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt++) {
            assertThrows(BizException.class,
                    () -> lifecycleService.verify(EMAIL, "000000", IP));
        }
        assertEquals(io.kbrag.domain.enums.EmailVerificationStatus.VERIFIED, verified.getStatus());
        assertEquals(ticketHash, verified.getTicketHash());
        assertEquals(0, verified.getAttemptsRemaining());
        assertNull(verified.getCodeHmac());
        verify(verificationMapper, times(7)).updateById(verified);

        RegistrationApplicationMapper applicationMapper = mock(RegistrationApplicationMapper.class);
        RegistrationSubmissionClaimMapper submissionClaimMapper =
                mock(RegistrationSubmissionClaimMapper.class);
        BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);
        when(verificationMapper.selectByTicketHash(ticketHash)).thenReturn(verified);
        when(verificationMapper.selectByTicketHashForUpdate(ticketHash)).thenReturn(verified);
        when(verificationMapper.consumeVerifiedTicket(anyString(), eq(ticketHash), any())).thenReturn(1);
        when(passwordEncoder.encode("StrongPassword!1")).thenReturn("bcrypt-hash");
        when(applicationMapper.insert(any(RegistrationApplication.class))).thenReturn(1);
        when(submissionClaimMapper.selectTicketHashForUpdate(SUBMISSION_ID)).thenReturn(ticketHash);
        RegistrationService registrationService = registrationService(
                applicationMapper, submissionClaimMapper, passwordEncoder);

        RegistrationSubmitted submitted = registrationService.submit(
                ticket.registrationTicket(), SUBMISSION_ID,
                "Alice", null, "StrongPassword!1", null, IP);

        assertEquals(EMAIL, submitted.email());
        verify(applicationMapper).insert(any(RegistrationApplication.class));
    }

    @Test
    void shouldReplaceTheOldTicketOnlyAfterTheNewCodeSucceedsAndSupportRefreshRecovery() {
        Clock lifecycleClock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        EmailVerificationAttemptService realAttemptService =
                new EmailVerificationAttemptService(verificationMapper, registrationHmac);
        EmailVerificationIssuanceService issuanceService =
                new EmailVerificationIssuanceService(verificationMapper, identityClaimService);
        EmailVerificationService lifecycleService = new EmailVerificationService(
                mailSender, properties, rateLimiter, realAttemptService, issuanceService,
                deliveryService, new RegistrationMailBulkhead(properties), registrationHmac,
                secureRandom, lifecycleClock);
        lifecycleService.requestCode(EMAIL, IP);
        VerifiedEmailTicket oldTicket = lifecycleService.verify(EMAIL, "123456", IP);
        String oldTicketHash = stored.get().getTicketHash();

        lifecycleService.requestCode(EMAIL, IP);
        assertEquals(oldTicketHash, stored.get().getTicketHash());
        VerifiedEmailTicket replacement = lifecycleService.verify(EMAIL, "123456", IP);
        EmailVerification verified = stored.get();
        String replacementHash = verified.getTicketHash();

        assertNotEquals(oldTicket.registrationTicket(), replacement.registrationTicket());
        assertNotEquals(oldTicketHash, replacementHash);
        assertEquals(io.kbrag.common.util.HashUtil.sha256Hex(replacement.registrationTicket()),
                replacementHash);

        RegistrationApplicationMapper applicationMapper = mock(RegistrationApplicationMapper.class);
        RegistrationSubmissionClaimMapper submissionClaimMapper =
                mock(RegistrationSubmissionClaimMapper.class);
        BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);
        when(verificationMapper.selectByTicketHash(oldTicketHash)).thenReturn(null);
        when(verificationMapper.selectByTicketHash(replacementHash)).thenReturn(verified);
        when(verificationMapper.selectByTicketHashForUpdate(oldTicketHash)).thenReturn(null);
        when(verificationMapper.selectByTicketHashForUpdate(replacementHash)).thenReturn(verified);
        when(verificationMapper.consumeVerifiedTicket(anyString(), eq(replacementHash), any()))
                .thenReturn(1);
        when(passwordEncoder.encode("StrongPassword!1")).thenReturn("bcrypt-hash");
        when(applicationMapper.insert(any(RegistrationApplication.class))).thenReturn(1);
        when(submissionClaimMapper.selectTicketHashForUpdate(REPLACEMENT_SUBMISSION_ID))
                .thenReturn(replacementHash);
        RegistrationService registrationService = registrationService(
                applicationMapper, submissionClaimMapper, passwordEncoder);

        assertThrows(BizException.class, () -> registrationService.submit(
                oldTicket.registrationTicket(), SUBMISSION_ID,
                "Alice", null, "StrongPassword!1", null, IP));
        RegistrationSubmitted submitted = registrationService.submit(
                replacement.registrationTicket(), REPLACEMENT_SUBMISSION_ID,
                "Alice", null, "StrongPassword!1", null, IP);

        assertEquals(EMAIL, submitted.email());
        verify(applicationMapper).insert(any(RegistrationApplication.class));
    }

    private RegistrationService registrationService(
            RegistrationApplicationMapper applicationMapper,
            RegistrationSubmissionClaimMapper submissionClaimMapper,
            BCryptPasswordEncoder passwordEncoder) {
        RegistrationSubmissionLookup lookup = new RegistrationSubmissionLookup(applicationMapper);
        RegistrationSubmissionPreflight preflight =
                new RegistrationSubmissionPreflight(lookup, verificationMapper);
        RegistrationSubmissionTransaction transaction = new RegistrationSubmissionTransaction(
                verificationMapper, applicationMapper, identityClaimService,
                lookup, submissionClaimMapper);
        return new RegistrationService(properties, rateLimiter, preflight,
                new RegistrationPasswordHasher(passwordEncoder, properties), transaction);
    }

    @Test
    void shouldReturnTheCeilingOfTheActualCooldownRemaining() {
        service.requestCode(EMAIL, IP);
        stored.get().setResendAvailableAt(LocalDateTime.of(2026, 8, 31, 8, 0, 5, 100_000_000));

        VerificationCodeRequested response = service.requestCode(EMAIL, IP);

        assertEquals(new VerificationCodeRequested(6), response);
    }

    @Test
    void shouldCompensateOnlyTheNewChallengeWhenSmtpDeliveryFails() {
        doThrow(new IllegalStateException("smtp timeout"))
                .when(mailSender).send(anyString(), anyString(), anyString());

        BizException exception = assertThrows(BizException.class,
                () -> service.requestCode(EMAIL, IP));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        EmailVerification candidate = stored.get();
        verify(deliveryService).cancel(
                candidate.getVerificationId(), candidate.getCodeHmac(),
                LocalDateTime.of(2026, 8, 31, 8, 0));
    }

    @Test
    void shouldNotChangeReusableChallengeStateWhenItsReminderMailFails() {
        service.requestCode(EMAIL, IP);
        doThrow(new IllegalStateException("smtp timeout"))
                .when(mailSender).send(anyString(), anyString(), anyString());

        assertThrows(BizException.class, () -> service.requestCode(EMAIL, IP));

        verify(deliveryService, never()).cancel(anyString(), anyString(), any());
        verify(deliveryService, times(1)).complete(anyString(), anyString(), any());
        verify(verificationMapper, never()).updateById(any(EmailVerification.class));
    }

    @Test
    void shouldRejectAConcurrentRequestUntilTheFirstSmtpDeliveryIsConfirmed() throws Exception {
        CountDownLatch smtpEntered = new CountDownLatch(1);
        CountDownLatch smtpReleased = new CountDownLatch(1);
        doAnswer(invocation -> {
            smtpEntered.countDown();
            if (!smtpReleased.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test SMTP wait timed out");
            }
            return null;
        }).when(mailSender).send(anyString(), anyString(), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VerificationCodeRequested> first = executor.submit(
                    () -> service.requestCode(EMAIL, IP));
            org.junit.jupiter.api.Assertions.assertTrue(
                    smtpEntered.await(5, TimeUnit.SECONDS));

            BizException concurrent = assertThrows(BizException.class,
                    () -> service.requestCode(EMAIL, IP));

            assertEquals(ErrorCode.RATE_LIMITED, concurrent.getErrorCode());
            assertEquals(VerificationCodeDeliveryStatus.ISSUING,
                    stored.get().getCodeDeliveryStatus());
            smtpReleased.countDown();
            assertEquals(new VerificationCodeRequested(60), first.get(5, TimeUnit.SECONDS));
            assertEquals(VerificationCodeDeliveryStatus.DELIVERED,
                    stored.get().getCodeDeliveryStatus());
            verify(mailSender, times(1)).send(anyString(), anyString(), anyString());
        } finally {
            smtpReleased.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldFailClosedWhenDeliveredStateCannotBeConfirmed() {
        when(deliveryService.complete(anyString(), anyString(), any())).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.requestCode(EMAIL, IP));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        EmailVerification candidate = stored.get();
        verify(deliveryService).cancel(candidate.getVerificationId(), candidate.getCodeHmac(),
                LocalDateTime.of(2026, 8, 31, 8, 0));
    }

    @Test
    void shouldCreateAStableKeyedHashForTheSameSourceIp() {
        String first = service.requestIpHash(IP);
        String second = service.requestIpHash(IP);
        String other = service.requestIpHash("203.0.113.10");

        assertEquals(first, second);
        assertNotEquals(first, other);
        assertFalse(first.contains(IP));
        assertEquals(64, first.length());
    }

    @Test
    void shouldFailClosedBeforeCreatingStateWhenMailIsUnavailable() {
        when(mailSender.available()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> service.requestCode(EMAIL, IP));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        verifyNoInteractions(rateLimiter);
        verify(verificationMapper, never()).insertIfAbsent(any());
    }

    @Test
    void shouldIssueTicketExpiryInTheServersDefaultTimeZone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            EmailVerificationService defaultClockService = new EmailVerificationService(
                    mailSender, properties, rateLimiter, attemptService,
                    new EmailVerificationIssuanceService(verificationMapper, identityClaimService),
                    deliveryService, new RegistrationMailBulkhead(properties), registrationHmac);
            when(attemptService.verify(eq(EMAIL), eq("123456"), anyString(), any(), any()))
                    .thenReturn(EmailVerificationAttemptService.VerificationAttempt.VERIFIED);
            LocalDateTime before = LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(15);

            VerifiedEmailTicket ticket = defaultClockService.verify(EMAIL, "123456", IP);

            org.mockito.ArgumentCaptor<LocalDateTime> expiry =
                    org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
            org.mockito.ArgumentCaptor<String> ticketHash =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(attemptService).verify(eq(EMAIL), eq("123456"), ticketHash.capture(),
                    expiry.capture(), any());
            LocalDateTime after = LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(15);
            assertEquals(43, ticket.registrationTicket().length());
            assertEquals(io.kbrag.common.util.HashUtil.sha256Hex(ticket.registrationTicket()),
                    ticketHash.getValue());
            assertNotEquals(ticket.registrationTicket(), ticketHash.getValue());
            assertFalse(expiry.getValue().isBefore(before));
            assertFalse(expiry.getValue().isAfter(after));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void shouldRejectRandomEmailAtTheRateLimiterBeforeVerificationStorageLookup() {
        BizException limited = new BizException(ErrorCode.RATE_LIMITED, "limited");
        org.mockito.Mockito.doThrow(limited)
                .when(rateLimiter).acquireVerificationAttempt(IP);

        BizException actual = assertThrows(BizException.class,
                () -> service.verify("random@example.com", "000000", IP));

        assertEquals(ErrorCode.RATE_LIMITED, actual.getErrorCode());
        verifyNoInteractions(attemptService);
    }

    private String latestCodeFromMailBody() {
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(2)).send(anyString(), anyString(), body.capture());
        String latest = body.getAllValues().get(1);
        return latest.replaceAll(".*?(\\d{6}).*", "$1");
    }

    private RegistrationProperties properties() {
        RegistrationProperties value = new RegistrationProperties();
        value.setEnabled(true);
        value.setCodeHmacKey("0123456789abcdef0123456789abcdef");
        value.setCodeTtlMinutes(10);
        value.setTicketTtlMinutes(15);
        value.setResendSeconds(60);
        value.setMaxAttempts(5);
        return value;
    }
}
