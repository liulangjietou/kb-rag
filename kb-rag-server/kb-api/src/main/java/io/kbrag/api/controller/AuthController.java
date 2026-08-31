package io.kbrag.api.controller;

import io.kbrag.api.dto.ChangePasswordRequest;
import io.kbrag.api.dto.CaptchaChallengeResponse;
import io.kbrag.api.dto.CaptchaTrackPointRequest;
import io.kbrag.api.dto.CaptchaVerifyRequest;
import io.kbrag.api.dto.CaptchaVerifyResponse;
import io.kbrag.api.dto.LoginRequest;
import io.kbrag.api.dto.LoginResponse;
import io.kbrag.api.dto.MeResponse;
import io.kbrag.api.dto.SsoAvailabilityResponse;
import io.kbrag.api.filter.AuthInterceptor;
import io.kbrag.api.security.ClientIpResolver;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.AuthService;
import io.kbrag.app.auth.LoginCaptchaChallenge;
import io.kbrag.app.auth.LoginCaptchaProof;
import io.kbrag.app.auth.LoginCaptchaService;
import io.kbrag.app.auth.LoginCaptchaTrackPoint;
import io.kbrag.app.auth.LoginTicket;
import io.kbrag.app.auth.TokenStore;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.enums.LoginMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Console authentication endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenStore tokenStore;
    private final LoginCaptchaService loginCaptchaService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 签发一个与当前直连来源绑定的滑块挑战。
     *
     * @param servlet servlet request
     * @return challenge 及其归一化坐标范围
     */
    @PostMapping("/captcha/challenge")
    public Result<CaptchaChallengeResponse> captchaChallenge(HttpServletRequest servlet) {
        LoginCaptchaChallenge challenge = loginCaptchaService.issue(
                clientIpResolver.resolve(servlet), servlet.getHeader(HttpHeaders.USER_AGENT));
        return Result.success(new CaptchaChallengeResponse(challenge.challengeId(), challenge.trackScale(),
                challenge.expiresInSeconds(), challenge.backgroundImage(), challenge.pieceImage(),
                challenge.imageWidth(), challenge.imageHeight(), challenge.pieceWidth(),
                challenge.pieceHeight(), challenge.pieceY()));
    }

    /**
     * 原子消费 challenge 并校验浏览器提交的滑动轨迹。
     *
     * @param request 轨迹请求
     * @param servlet servlet request
     * @return 短期一次性登录 proof
     */
    @PostMapping("/captcha/verify")
    public Result<CaptchaVerifyResponse> captchaVerify(@Valid @RequestBody CaptchaVerifyRequest request,
                                                       HttpServletRequest servlet) {
        LoginCaptchaProof proof = loginCaptchaService.verify(request.challengeId(), mapTrack(request.track()),
                clientIpResolver.resolve(servlet), servlet.getHeader(HttpHeaders.USER_AGENT));
        return Result.success(new CaptchaVerifyResponse(proof.proof(), proof.expiresInSeconds()));
    }

    /**
     * Verifies credentials and issues a session token.
     *
     * @param request  login payload
     * @param servlet  servlet request, used to resolve the source address for the audit
     * @return session token and the mandatory password rotation flag
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
        // proof 必须先于密码、LDAP 和登录审计消费：验证码失败不是账号登录尝试。
        String clientIp = clientIpResolver.resolve(servlet);
        loginCaptchaService.consume(request.captchaProof(), clientIp,
                servlet.getHeader(HttpHeaders.USER_AGENT));
        LoginTicket ticket = authService.login(request.username(), request.password(),
                parseMode(request.mode()), clientIp);
        return Result.success(new LoginResponse(ticket.token(), ticket.mustChangePassword()));
    }

    /**
     * Tells the login page whether the single sign-on tab is worth offering.
     *
     * <p>Unauthenticated on purpose: the page has to render before anybody has a session, and the answer
     * is one boolean about how this deployment is wired, not about any account.
     *
     * @return availability of the directory entry point
     */
    @GetMapping("/sso-available")
    public Result<SsoAvailabilityResponse> ssoAvailable() {
        return Result.success(new SsoAvailabilityResponse(authService.singleSignOnAvailable()));
    }

    /**
     * Rotates the password of the caller and ends every existing session.
     *
     * @param request  rotation payload
     * @param username authenticated user
     * @return empty success envelope
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username) {
        authService.changePassword(username, request.oldPassword(), request.newPassword());
        return Result.success(null);
    }

    /**
     * Returns the authenticated account together with the permissions of the session.
     *
     * @param username authenticated user
     * @return account view
     */
    @GetMapping("/me")
    public Result<MeResponse> me(@RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username) {
        AdminUser user = authService.currentUser(username);
        return Result.success(MeResponse.from(user, AccessGuard.currentUser()));
    }

    /**
     * Ends the current session.
     *
     * @param token bearer token of the current session
     * @return empty success envelope
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute(AuthInterceptor.ATTR_TOKEN) String token) {
        tokenStore.revoke(token);
        return Result.success(null);
    }

    private LoginMode parseMode(String mode) {
        try {
            return LoginMode.from(mode);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("mode 仅支持 LOCAL 或 SSO");
        }
    }

    private List<LoginCaptchaTrackPoint> mapTrack(List<CaptchaTrackPointRequest> track) {
        if (track == null) {
            return null;
        }
        return track.stream()
                .map(point -> point == null ? null
                        : new LoginCaptchaTrackPoint(
                                point.x() == null ? Integer.MIN_VALUE : point.x(),
                                point.y() == null ? Integer.MIN_VALUE : point.y(),
                                point.elapsedMs() == null ? Long.MIN_VALUE : point.elapsedMs()))
                .toList();
    }
}
