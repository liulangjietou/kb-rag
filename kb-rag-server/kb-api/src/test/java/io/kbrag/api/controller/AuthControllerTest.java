package io.kbrag.api.controller;

import io.kbrag.api.dto.CaptchaTrackPointRequest;
import io.kbrag.api.dto.CaptchaChallengeResponse;
import io.kbrag.api.dto.CaptchaVerifyRequest;
import io.kbrag.api.dto.LoginRequest;
import io.kbrag.api.security.ClientIpResolver;
import io.kbrag.app.auth.AuthService;
import io.kbrag.app.auth.LoginCaptchaChallenge;
import io.kbrag.app.auth.LoginCaptchaProof;
import io.kbrag.app.auth.LoginCaptchaService;
import io.kbrag.app.auth.LoginCaptchaTrackPoint;
import io.kbrag.app.auth.LoginTicket;
import io.kbrag.app.auth.ConsoleSessionService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.LoginMode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化验证码 Controller 的来源选择和登录前置消费顺序。
 *
 * @author owlzhangfq@gmail.com
 */
class AuthControllerTest {

    private static final String REMOTE_ADDRESS = "10.0.0.8";
    private static final String FORWARDED_ADDRESS = "198.51.100.8";
    private static final String USER_AGENT = "controller-test-browser";

    private AuthService authService;
    private LoginCaptchaService captchaService;
    private ClientIpResolver clientIpResolver;
    private HttpServletRequest servlet;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        captchaService = mock(LoginCaptchaService.class);
        clientIpResolver = mock(ClientIpResolver.class);
        servlet = mock(HttpServletRequest.class);
        when(servlet.getRemoteAddr()).thenReturn(REMOTE_ADDRESS);
        when(servlet.getHeader(HttpHeaders.USER_AGENT)).thenReturn(USER_AGENT);
        when(servlet.getHeader("X-Forwarded-For")).thenReturn(FORWARDED_ADDRESS);
        when(clientIpResolver.resolve(servlet)).thenReturn(FORWARDED_ADDRESS);
        controller = new AuthController(authService, mock(ConsoleSessionService.class), captchaService, clientIpResolver);
    }

    @Test
    void shouldUseTheResolvedAddressForTheWholeCaptchaAndLoginChain() {
        when(captchaService.issue(FORWARDED_ADDRESS, USER_AGENT))
                .thenReturn(new LoginCaptchaChallenge("challenge", 1_000, 120,
                        "data:image/png;base64,background", "data:image/png;base64,piece",
                        320, 160, 48, 48, 56));
        when(captchaService.verify("challenge", List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(1_000, 0, 400)), FORWARDED_ADDRESS, USER_AGENT))
                .thenReturn(new LoginCaptchaProof("proof", 60));

        CaptchaChallengeResponse challenge = controller.captchaChallenge(servlet).getData();
        assertEquals("challenge", challenge.challengeId());
        assertEquals("data:image/png;base64,background", challenge.backgroundImage());
        assertEquals("proof", controller.captchaVerify(new CaptchaVerifyRequest("challenge", List.of(
                new CaptchaTrackPointRequest(0, 0, 0L),
                new CaptchaTrackPointRequest(1_000, 0, 400L))), servlet).getData().captchaProof());

        verify(captchaService).issue(FORWARDED_ADDRESS, USER_AGENT);
        verify(captchaService).verify("challenge", List.of(
                new LoginCaptchaTrackPoint(0, 0, 0),
                new LoginCaptchaTrackPoint(1_000, 0, 400)), FORWARDED_ADDRESS, USER_AGENT);
    }

    @Test
    void shouldConsumeCaptchaBeforeCallingAuthentication() {
        LoginRequest request = new LoginRequest("admin", "secret", "LOCAL", "proof");
        when(authService.login("admin", "secret", LoginMode.LOCAL, FORWARDED_ADDRESS))
                .thenReturn(new LoginTicket("token", false));

        controller.login(request, servlet);

        InOrder order = inOrder(captchaService, authService);
        order.verify(captchaService).consume("proof", FORWARDED_ADDRESS, USER_AGENT);
        order.verify(authService).login("admin", "secret", LoginMode.LOCAL, FORWARDED_ADDRESS);
    }

    @Test
    void shouldNotCallAuthenticationWhenCaptchaConsumptionFails() {
        LoginRequest request = new LoginRequest("admin", "bad-secret", "LOCAL", "invalid-proof");
        org.mockito.Mockito.doThrow(BizException.invalidParam("滑块验证已失效，请重新验证"))
                .when(captchaService).consume("invalid-proof", FORWARDED_ADDRESS, USER_AGENT);

        assertThrows(BizException.class, () -> controller.login(request, servlet));

        verify(authService, never()).login(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
