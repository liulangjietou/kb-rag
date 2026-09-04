package io.kbrag.api.controller;

import io.kbrag.api.dto.RegistrationVerificationCodeRequest;
import io.kbrag.api.dto.SubmitRegistrationRequest;
import io.kbrag.api.dto.VerifyRegistrationEmailRequest;
import io.kbrag.api.security.ClientIpResolver;
import io.kbrag.app.auth.LoginCaptchaService;
import io.kbrag.app.registration.EmailVerificationService;
import io.kbrag.app.registration.RegistrationService;
import io.kbrag.app.registration.RegistrationSubmitted;
import io.kbrag.app.registration.VerificationCodeRequested;
import io.kbrag.app.registration.VerifiedEmailTicket;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化注册发码必须先消费滑块 proof，失败时绝不触达邮件服务。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationControllerTest {

    private static final String EMAIL = "person@example.com";
    private static final String IP = "203.0.113.9";
    private static final String USER_AGENT = "registration-test-browser";

    private EmailVerificationService verificationService;
    private RegistrationService registrationService;
    private LoginCaptchaService captchaService;
    private HttpServletRequest servlet;
    private RegistrationController controller;

    @BeforeEach
    void setUp() {
        verificationService = mock(EmailVerificationService.class);
        registrationService = mock(RegistrationService.class);
        captchaService = mock(LoginCaptchaService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        servlet = mock(HttpServletRequest.class);
        when(ipResolver.resolve(servlet)).thenReturn(IP);
        when(servlet.getHeader(HttpHeaders.USER_AGENT)).thenReturn(USER_AGENT);
        controller = new RegistrationController(verificationService, registrationService,
                captchaService, ipResolver);
    }

    @Test
    void shouldConsumeCaptchaBeforeIssuingCode() {
        RegistrationVerificationCodeRequest request =
                new RegistrationVerificationCodeRequest(EMAIL, "proof");
        when(verificationService.requestCode(EMAIL, IP)).thenReturn(new VerificationCodeRequested(60));

        controller.requestVerificationCode(request, servlet);

        InOrder order = inOrder(captchaService, verificationService);
        order.verify(captchaService).consume("proof", IP, USER_AGENT);
        order.verify(verificationService).requestCode(EMAIL, IP);
    }

    @Test
    void shouldNotIssueCodeWhenCaptchaConsumptionFails() {
        RegistrationVerificationCodeRequest request =
                new RegistrationVerificationCodeRequest(EMAIL, "invalid-proof");
        org.mockito.Mockito.doThrow(BizException.invalidParam("captcha invalid"))
                .when(captchaService).consume("invalid-proof", IP, USER_AGENT);

        assertThrows(BizException.class, () -> controller.requestVerificationCode(request, servlet));

        verify(verificationService, never()).requestCode(EMAIL, IP);
    }

    @Test
    void shouldResolveTrustedIpForEmailVerification() {
        VerifyRegistrationEmailRequest request =
                new VerifyRegistrationEmailRequest(EMAIL, "123456");
        when(verificationService.verify(EMAIL, "123456", IP))
                .thenReturn(new VerifiedEmailTicket("a".repeat(43), 900));

        controller.verifyEmail(request, servlet);

        verify(verificationService).verify(EMAIL, "123456", IP);
    }

    @Test
    void shouldResolveTrustedIpForFinalSubmission() {
        String submissionId = "123e4567-e89b-42d3-a456-426614174000";
        SubmitRegistrationRequest request = new SubmitRegistrationRequest(
                "a".repeat(43), submissionId,
                "Alice", "Platform", "StrongPassword!1", "note");
        when(registrationService.submit("a".repeat(43), submissionId, "Alice", "Platform",
                "StrongPassword!1", "note", IP))
                .thenReturn(new RegistrationSubmitted(
                        "reg_1", EMAIL, "PENDING", LocalDateTime.now()));

        controller.submit(request, servlet);

        verify(registrationService).submit("a".repeat(43), submissionId, "Alice", "Platform",
                "StrongPassword!1", "note", IP);
    }
}
