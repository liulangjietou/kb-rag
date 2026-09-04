package io.kbrag.app.registration;

import io.kbrag.app.auth.EmailIdentityClaimService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import io.kbrag.domain.mapper.RegistrationSubmissionClaimMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 固化 ticket 绑定邮箱、一次性消费与驳回后重新申请。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationServiceTest {

    private static final String TICKET = "a".repeat(43);
    private static final String SUBMISSION_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String EMAIL = "verified@example.com";
    private static final String PASSWORD = "StrongPassword!1";

    private final EmailVerificationMapper verificationMapper = mock(EmailVerificationMapper.class);
    private final RegistrationApplicationMapper applicationMapper = mock(RegistrationApplicationMapper.class);
    private final RegistrationSubmissionClaimMapper submissionClaimMapper =
            mock(RegistrationSubmissionClaimMapper.class);
    private final EmailIdentityClaimService identityClaimService =
            mock(EmailIdentityClaimService.class);
    private final BCryptPasswordEncoder passwordEncoder = mock(BCryptPasswordEncoder.class);
    private final RegistrationProperties properties = new RegistrationProperties();
    private final RegistrationRateLimiter rateLimiter = mock(RegistrationRateLimiter.class);
    private final RegistrationSubmissionLookup submissionLookup =
            new RegistrationSubmissionLookup(applicationMapper);
    private final RegistrationSubmissionPreflight submissionPreflight =
            new RegistrationSubmissionPreflight(submissionLookup, verificationMapper);
    private final RegistrationPasswordHasher passwordHasher =
            new RegistrationPasswordHasher(passwordEncoder, properties);
    private final RegistrationSubmissionTransaction submissionTransaction =
            new RegistrationSubmissionTransaction(
                    verificationMapper, applicationMapper, identityClaimService,
                    submissionLookup, submissionClaimMapper);
    private final RegistrationService service = new RegistrationService(
            properties, rateLimiter, submissionPreflight, passwordHasher, submissionTransaction);
    private EmailVerification verification;

    @BeforeEach
    void setUp() {
        verification = new EmailVerification();
        verification.setVerificationId("evf_1");
        verification.setEmail(EMAIL);
        verification.setStatus(EmailVerificationStatus.VERIFIED);
        verification.setVerifiedAt(LocalDateTime.now().minusMinutes(1));
        verification.setTicketExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(verificationMapper.selectByTicketHashForUpdate(HashUtil.sha256Hex(TICKET)))
                .thenReturn(verification);
        when(verificationMapper.selectByTicketHash(HashUtil.sha256Hex(TICKET)))
                .thenReturn(verification);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
        when(applicationMapper.insert(any(RegistrationApplication.class))).thenReturn(1);
        when(submissionClaimMapper.selectTicketHashForUpdate(SUBMISSION_ID))
                .thenReturn(HashUtil.sha256Hex(TICKET));
        when(verificationMapper.consumeVerifiedTicket(any(), any(), any())).thenReturn(1);
    }

    @Test
    void shouldDeriveEmailOnlyFromTicketAndPersistPendingHash() {
        RegistrationSubmitted submitted = service.submit(
                TICKET, SUBMISSION_ID, " Alice ", " Platform ", PASSWORD,
                " Build search ", "203.0.113.9");

        ArgumentCaptor<RegistrationApplication> application =
                ArgumentCaptor.forClass(RegistrationApplication.class);
        verify(applicationMapper).insert(application.capture());
        assertEquals(EMAIL, submitted.email());
        assertEquals(EMAIL, application.getValue().getEmail());
        assertEquals(SUBMISSION_ID, application.getValue().getSubmissionId());
        assertEquals(HashUtil.sha256Hex(TICKET), application.getValue().getSubmissionTicketHash());
        assertEquals("bcrypt-hash", application.getValue().getPasswordHash());
        assertEquals(RegistrationApplicationStatus.PENDING, application.getValue().getStatus());
        verify(verificationMapper).consumeVerifiedTicket(
                "evf_1", HashUtil.sha256Hex(TICKET), application.getValue().getCreatedAt());
        InOrder order = inOrder(passwordEncoder, applicationMapper, verificationMapper);
        order.verify(applicationMapper).selectBySubmissionId(SUBMISSION_ID);
        order.verify(verificationMapper).selectByTicketHash(HashUtil.sha256Hex(TICKET));
        order.verify(passwordEncoder).encode(PASSWORD);
        order.verify(applicationMapper).selectBySubmissionId(SUBMISSION_ID);
        order.verify(verificationMapper).selectByTicketHashForUpdate(HashUtil.sha256Hex(TICKET));
    }

    @Test
    void shouldOpenTheDatabaseTransactionOnlyAfterTheNonTransactionalBcryptFacade() throws Exception {
        assertNull(RegistrationService.class.getMethod("submit", String.class, String.class,
                String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class));
        assertNotNull(RegistrationSubmissionTransaction.class
                .getMethod("submit", RegistrationSubmissionCommand.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void shouldRejectWhenTicketWasConsumedByAnotherRequest() {
        when(verificationMapper.consumeVerifiedTicket(any(), any(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.submit(TICKET, SUBMISSION_ID,
                        "Alice", null, PASSWORD, null, "203.0.113.9"));
    }

    @Test
    void shouldRejectUnicodeCharactersOutsideBase64UrlAlphabet() {
        String unicodeTicket = "a".repeat(42) + "中";

        assertThrows(BizException.class,
                () -> service.submit(unicodeTicket, SUBMISSION_ID,
                        "Alice", null, PASSWORD, null, "203.0.113.9"));

        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void shouldFailClosedBeforeConsumingATicketWhenRegistrationIsDisabled() {
        properties.setEnabled(false);

        BizException exception = assertThrows(BizException.class,
                () -> service.submit(TICKET, SUBMISSION_ID,
                        "Alice", null, PASSWORD, null, "203.0.113.9"));

        assertEquals(io.kbrag.common.api.ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        verifyNoInteractions(rateLimiter);
        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
    }

    @Test
    void shouldCreateANewApplicationWithoutOverwritingRejectedReviewHistory() {
        RegistrationApplication rejected = new RegistrationApplication();
        rejected.setId(9L);
        rejected.setApplicationId("reg_existing");
        rejected.setEmail(EMAIL);
        rejected.setStatus(RegistrationApplicationStatus.REJECTED);
        rejected.setReviewedBy("usr_reviewer");
        rejected.setReviewedAt(LocalDateTime.now().minusDays(1));
        rejected.setReviewReason("old reason");
        rejected.setApprovedTenantId("tnt_old");
        rejected.setApprovedUserId("usr_old");
        when(applicationMapper.selectByEmailForUpdate(EMAIL)).thenReturn(rejected);

        service.submit(TICKET, SUBMISSION_ID, "Alice", null, PASSWORD, null, "203.0.113.9");

        ArgumentCaptor<RegistrationApplication> created =
                ArgumentCaptor.forClass(RegistrationApplication.class);
        verify(applicationMapper).insert(created.capture());
        assertEquals(RegistrationApplicationStatus.PENDING, created.getValue().getStatus());
        assertEquals(RegistrationApplicationStatus.REJECTED, rejected.getStatus());
        assertEquals("usr_reviewer", rejected.getReviewedBy());
        assertEquals("old reason", rejected.getReviewReason());
        assertEquals("tnt_old", rejected.getApprovedTenantId());
        assertEquals("usr_old", rejected.getApprovedUserId());
        verify(applicationMapper, never()).updateById(any(RegistrationApplication.class));
    }

    @Test
    void shouldRejectAnIdentityClaimEvenWhenItsOwningAccountWasLogicallyDeleted() {
        when(identityClaimService.claimed(EMAIL)).thenReturn(true);

        assertThrows(BizException.class, () -> service.submit(
                TICKET, SUBMISSION_ID, "Alice", null, PASSWORD, null, "203.0.113.9"));

        verify(applicationMapper, never()).insert(any(RegistrationApplication.class));
        verify(verificationMapper, never()).consumeVerifiedTicket(any(), any(), any());
        verify(identityClaimService).claimed(EMAIL);
    }

    @Test
    void shouldRejectRandomTicketAtTheRateLimiterBeforeStorageLookup() {
        String clientIp = "203.0.113.10";
        org.mockito.Mockito.doThrow(new BizException(
                        io.kbrag.common.api.ErrorCode.RATE_LIMITED, "limited"))
                .when(rateLimiter).acquireSubmissionAttempt(clientIp);

        BizException actual = assertThrows(BizException.class,
                () -> service.submit("z".repeat(43), SUBMISSION_ID,
                        "Alice", null, PASSWORD, null, clientIp));

        assertEquals(io.kbrag.common.api.ErrorCode.RATE_LIMITED, actual.getErrorCode());
        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
    }

    @Test
    void shouldRejectAnUnknownTicketBeforeUsingBcrypt() {
        String unknownTicket = "z".repeat(43);
        when(verificationMapper.selectByTicketHash(HashUtil.sha256Hex(unknownTicket)))
                .thenReturn(null);

        assertThrows(BizException.class, () -> service.submit(
                unknownTicket, SUBMISSION_ID, "Alice", null, PASSWORD, null,
                "203.0.113.9"));

        verify(passwordEncoder, never()).encode(any());
        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
    }

    @Test
    void shouldRejectASubmissionIdAlreadyClaimedByAnotherTicket() {
        when(submissionClaimMapper.selectTicketHashForUpdate(SUBMISSION_ID))
                .thenReturn(HashUtil.sha256Hex("b".repeat(43)));

        BizException exception = assertThrows(BizException.class, () -> service.submit(
                TICKET, SUBMISSION_ID, "Alice", null, PASSWORD, null,
                "203.0.113.9"));

        assertEquals(io.kbrag.common.api.ErrorCode.INVALID_PARAM,
                exception.getErrorCode());
        verify(applicationMapper, never()).insert(any(RegistrationApplication.class));
        verify(verificationMapper, never()).consumeVerifiedTicket(any(), any(), any());
    }

    @Test
    void shouldReturnTheOriginalReceiptWhenACommittedResponseIsRetried() {
        RegistrationApplication existing = submittedApplication(HashUtil.sha256Hex(TICKET));
        when(applicationMapper.selectBySubmissionId(SUBMISSION_ID)).thenReturn(existing);

        RegistrationSubmitted retried = service.submit(
                TICKET, SUBMISSION_ID, "Changed", null, PASSWORD, null, "203.0.113.9");

        assertEquals("reg_committed", retried.applicationId());
        assertEquals(EMAIL, retried.email());
        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
        verify(verificationMapper, never()).consumeVerifiedTicket(any(), any(), any());
        verify(applicationMapper, never()).insert(any(RegistrationApplication.class));
    }

    @Test
    void shouldRejectAReusedSubmissionIdBoundToAnotherTicket() {
        RegistrationApplication existing = submittedApplication(HashUtil.sha256Hex("b".repeat(43)));
        when(applicationMapper.selectBySubmissionId(SUBMISSION_ID)).thenReturn(existing);

        assertThrows(BizException.class, () -> service.submit(
                TICKET, SUBMISSION_ID, "Alice", null, PASSWORD, null, "203.0.113.9"));

        verify(verificationMapper, never()).selectByTicketHashForUpdate(any());
    }

    @Test
    void shouldRecoverReceiptWhenConcurrentFirstRequestConsumesTicketBeforeLockAcquisition() {
        RegistrationApplication existing = submittedApplication(HashUtil.sha256Hex(TICKET));
        when(applicationMapper.selectBySubmissionId(SUBMISSION_ID))
                .thenReturn(null, null, existing);
        when(verificationMapper.selectByTicketHashForUpdate(any())).thenReturn(null);

        RegistrationSubmitted retried = service.submit(
                TICKET, SUBMISSION_ID, "Alice", null, PASSWORD, null, "203.0.113.9");

        assertEquals("reg_committed", retried.applicationId());
        verify(applicationMapper, never()).insert(any(RegistrationApplication.class));
    }

    private RegistrationApplication submittedApplication(String ticketHash) {
        RegistrationApplication existing = new RegistrationApplication();
        existing.setApplicationId("reg_committed");
        existing.setEmail(EMAIL);
        existing.setSubmissionId(SUBMISSION_ID);
        existing.setSubmissionTicketHash(ticketHash);
        existing.setStatus(RegistrationApplicationStatus.PENDING);
        existing.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return existing;
    }
}
