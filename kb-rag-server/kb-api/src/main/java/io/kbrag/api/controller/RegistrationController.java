package io.kbrag.api.controller;

import io.kbrag.api.dto.RegistrationSubmittedResponse;
import io.kbrag.api.dto.RegistrationVerificationCodeRequest;
import io.kbrag.api.dto.RegistrationVerificationCodeResponse;
import io.kbrag.api.dto.SubmitRegistrationRequest;
import io.kbrag.api.dto.VerifiedRegistrationEmailResponse;
import io.kbrag.api.dto.VerifyRegistrationEmailRequest;
import io.kbrag.api.security.ClientIpResolver;
import io.kbrag.app.auth.LoginCaptchaService;
import io.kbrag.app.registration.EmailVerificationService;
import io.kbrag.app.registration.PasswordPolicy;
import io.kbrag.app.registration.RegistrationService;
import io.kbrag.app.registration.RegistrationSubmitted;
import io.kbrag.app.registration.VerificationCodeRequested;
import io.kbrag.app.registration.VerifiedEmailTicket;
import io.kbrag.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无会话邮箱注册入口。
 *
 * <p>只有这三个精确 POST 路径进入公开白名单；发码前必须原子消费滑块 proof，最终提交的
 * 邮箱只来自服务端票据，申请成功也不会签发登录 token。
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/registrations")
@RequiredArgsConstructor
public class RegistrationController {

    private final EmailVerificationService emailVerificationService;
    private final RegistrationService registrationService;
    private final LoginCaptchaService loginCaptchaService;
    private final ClientIpResolver clientIpResolver;

    /** 消费滑块 proof 后发送邮箱验证码。 */
    @PostMapping("/verification-code")
    public Result<RegistrationVerificationCodeResponse> requestVerificationCode(
            @Valid @RequestBody RegistrationVerificationCodeRequest request,
            HttpServletRequest servlet) {
        String clientIp = clientIpResolver.resolve(servlet);
        loginCaptchaService.consume(request.captchaProof(), clientIp,
                servlet.getHeader(HttpHeaders.USER_AGENT));
        VerificationCodeRequested requested = emailVerificationService.requestCode(request.email(), clientIp);
        return Result.success(new RegistrationVerificationCodeResponse(requested.resendAfterSeconds()));
    }

    /** 校验验证码并返回一次性注册票据。 */
    @PostMapping("/verify-email")
    public Result<VerifiedRegistrationEmailResponse> verifyEmail(
            @Valid @RequestBody VerifyRegistrationEmailRequest request,
            HttpServletRequest servlet) {
        String clientIp = clientIpResolver.resolve(servlet);
        VerifiedEmailTicket ticket = emailVerificationService.verify(
                request.email(), request.code(), clientIp);
        return Result.success(new VerifiedRegistrationEmailResponse(
                ticket.registrationTicket(), ticket.expiresInSeconds()));
    }

    /** 提交 PENDING 申请；不会创建 session。 */
    @PostMapping
    public Result<RegistrationSubmittedResponse> submit(
            @Valid @RequestBody SubmitRegistrationRequest request,
            HttpServletRequest servlet) {
        // API 层 fast-fail 改善反馈；服务层仍用同一策略复核直接调用。
        PasswordPolicy.requireStrong(request.password());
        String clientIp = clientIpResolver.resolve(servlet);
        RegistrationSubmitted submitted = registrationService.submit(
                request.registrationTicket(), request.clientSubmissionId(),
                request.displayName(), request.teamName(),
                request.password(), request.applicationNote(), clientIp);
        return Result.success(RegistrationSubmittedResponse.from(submitted));
    }
}
