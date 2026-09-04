package io.kbrag.app.registration;

import io.kbrag.app.identity.EmailAddress;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.VerificationCodeDeliveryStatus;
import io.kbrag.domain.port.NotificationMailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 邮箱验证码、邮箱校验和一次性注册票据的生命周期服务。
 *
 * <p>数据库只保存验证码的 keyed HMAC 和票据的 SHA-256；验证码与票据明文均不会落盘、
 * 进入日志或出现在异常中。已存在账号也走相同的单行验证状态机，但只发送无凭据提示信，
 * 不会把随机验证码交付给用户，从响应和数据库访问路径两侧收窄账号枚举信号。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class EmailVerificationService {

    private static final int CODE_BOUND = 1_000_000;
    private static final int RANDOM_TOKEN_BYTES = 32;
    private static final String VERIFICATION_ID_PREFIX = "evf_";
    private static final int RANDOM_ID_LENGTH = 20;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final NotificationMailSender mailSender;
    private final RegistrationProperties properties;
    private final RegistrationRateLimiter rateLimiter;
    private final EmailVerificationAttemptService attemptService;
    private final EmailVerificationIssuanceService issuanceService;
    private final EmailVerificationDeliveryService deliveryService;
    private final RegistrationMailBulkhead mailBulkhead;
    private final RegistrationHmac registrationHmac;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public EmailVerificationService(NotificationMailSender mailSender,
                                    RegistrationProperties properties,
                                    RegistrationRateLimiter rateLimiter,
                                    EmailVerificationAttemptService attemptService,
                                    EmailVerificationIssuanceService issuanceService,
                                    EmailVerificationDeliveryService deliveryService,
                                    RegistrationMailBulkhead mailBulkhead,
                                    RegistrationHmac registrationHmac) {
        this(mailSender, properties, rateLimiter, attemptService, issuanceService,
                deliveryService, mailBulkhead, registrationHmac,
                new SecureRandom(), Clock.systemDefaultZone());
    }

    EmailVerificationService(NotificationMailSender mailSender,
                             RegistrationProperties properties,
                             RegistrationRateLimiter rateLimiter,
                             EmailVerificationAttemptService attemptService,
                             EmailVerificationIssuanceService issuanceService,
                             EmailVerificationDeliveryService deliveryService,
                             RegistrationMailBulkhead mailBulkhead,
                             RegistrationHmac registrationHmac,
                             SecureRandom secureRandom, Clock clock) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.attemptService = attemptService;
        this.issuanceService = issuanceService;
        this.deliveryService = deliveryService;
        this.mailBulkhead = mailBulkhead;
        this.registrationHmac = registrationHmac;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    /**
     * 申请注册验证码。
     *
     * @param submittedEmail 用户输入邮箱
     * @param clientIp       可信边界解析后的客户端地址
     * @return 固定公开结构
     */
    public VerificationCodeRequested requestCode(String submittedEmail, String clientIp) {
        requireMailReady();
        String email = EmailAddress.normalize(submittedEmail);
        rateLimiter.acquireCodeRequest(email, clientIp);
        // 舱壁在事务代理外取得；等待/拒绝以及后续 SMTP 均不会先占用数据库连接。
        return mailBulkhead.execute(() -> issueAndSend(email, clientIp));
    }

    private VerificationCodeRequested issueAndSend(String email, String clientIp) {
        LocalDateTime now = LocalDateTime.now(clock);
        String verificationId = businessId(VERIFICATION_ID_PREFIX);
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(CODE_BOUND));
        EmailVerification candidate = issuedState(verificationId, email, code, clientIp, now);
        VerificationIssuanceDecision decision = issuanceService.prepare(candidate, now);
        try {
            sendPublicNotification(email, decision.occupied(),
                    decision.challengeIssued() ? code : null);
        } catch (RuntimeException exception) {
            if (decision.challengeIssued()) {
                compensateFailedDelivery(candidate);
            }
            throw exception;
        }
        if (decision.challengeIssued()) {
            confirmDelivery(candidate);
            log.info("registration verification code issued, verificationId={}", verificationId);
        }
        return new VerificationCodeRequested(decision.resendAfterSeconds());
    }

    private void sendPublicNotification(String email, boolean occupied, String code) {
        if (occupied) {
            sendOccupiedNotification(email);
            return;
        }
        if (code == null) {
            sendMail(email, "Knowledge Atlas 注册验证码提示",
                    "验证码仍在有效期内，请使用最近一封验证码邮件完成验证。若非本人操作，可忽略本邮件。");
            return;
        }
        String body = "您的注册验证码是：" + code + "。验证码将在 "
                + properties.getCodeTtlMinutes() + " 分钟后失效，请勿转发给他人。";
        sendMail(email, "Knowledge Atlas 注册验证码", body);
    }

    private void sendOccupiedNotification(String email) {
        sendMail(email, "Knowledge Atlas 注册提示",
                "该邮箱已存在 Knowledge Atlas 账号，请直接前往登录。若非本人操作，可忽略本邮件。");
    }

    private EmailVerification issuedState(String verificationId, String email, String code,
                                          String clientIp, LocalDateTime now) {
        EmailVerification verification = new EmailVerification();
        verification.setVerificationId(verificationId);
        verification.setEmail(email);
        verification.setCodeHmac(registrationHmac.verificationCode(verificationId, email, code));
        verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.ISSUING);
        verification.setStatus(EmailVerificationStatus.ISSUED);
        verification.setAttemptsRemaining(properties.getMaxAttempts());
        verification.setExpiresAt(now.plusMinutes(properties.getCodeTtlMinutes()));
        verification.setResendAvailableAt(now.plusSeconds(properties.getResendSeconds()));
        verification.setRequestIpHash(requestIpHash(clientIp));
        return verification;
    }

    private void compensateFailedDelivery(EmailVerification candidate) {
        try {
            deliveryService.cancel(candidate.getVerificationId(), candidate.getCodeHmac(),
                    LocalDateTime.now(clock));
        } catch (RuntimeException exception) {
            log.error("registration verification compensation failed, errorCode={}, cause={}",
                    ErrorCode.INTERNAL_ERROR, exception.getClass().getSimpleName());
        }
    }

    private void confirmDelivery(EmailVerification candidate) {
        LocalDateTime deliveredAt = LocalDateTime.now(clock);
        if (deliveryService.complete(candidate.getVerificationId(), candidate.getCodeHmac(), deliveredAt) == 1) {
            return;
        }
        compensateFailedDelivery(candidate);
        log.error("registration verification delivery confirmation failed, errorCode={}",
                ErrorCode.INTERNAL_ERROR);
        throw new BizException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试");
    }

    /**
     * 校验验证码并签发一次性注册票据。
     *
     * @param submittedEmail 提交邮箱
     * @param code           六位验证码
     * @param clientIp       可信边界解析后的客户端地址
     * @return 仅返回一次的注册票据
     */
    public VerifiedEmailTicket verify(String submittedEmail, String code, String clientIp) {
        requireCoreReady();
        String email = EmailAddress.normalize(submittedEmail);
        rateLimiter.acquireVerificationAttempt(clientIp);
        LocalDateTime now = LocalDateTime.now(clock);
        String ticket = randomToken();
        String ticketHash = io.kbrag.common.util.HashUtil.sha256Hex(ticket);
        LocalDateTime ticketExpiresAt = now.plusMinutes(properties.getTicketTtlMinutes());
        EmailVerificationAttemptService.VerificationAttempt result = attemptService.verify(
                email, code, ticketHash, ticketExpiresAt, now);
        if (result != EmailVerificationAttemptService.VerificationAttempt.VERIFIED) {
            throw BizException.invalidParam("验证码无效或已过期，请重新获取");
        }
        return new VerifiedEmailTicket(ticket, properties.getTicketTtlMinutes() * 60L);
    }

    private void requireMailReady() {
        requireCoreReady();
        if (!mailSender.available()) {
            log.error("registration mail unavailable, errorCode={}", ErrorCode.INTERNAL_ERROR);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请联系管理员");
        }
    }

    private void requireCoreReady() {
        String key = properties.getCodeHmacKey();
        if (!properties.isEnabled() || key == null || key.length() < RANDOM_TOKEN_BYTES
                || properties.getCodeTtlMinutes() <= 0 || properties.getTicketTtlMinutes() <= 0
                || properties.getResendSeconds() <= 0 || properties.getMaxAttempts() <= 0) {
            log.error("registration security configuration unavailable, errorCode={}", ErrorCode.INTERNAL_ERROR);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请联系管理员");
        }
    }

    /** 同一来源在不同邮箱请求中产生稳定摘要，且不暴露 IP 明文。 */
    String requestIpHash(String clientIp) {
        return registrationHmac.sourceIp(clientIp);
    }

    private void sendMail(String email, String subject, String body) {
        try {
            mailSender.send(email, subject, body);
        } catch (RuntimeException exception) {
            // SMTP 异常消息可能携带收件人，只记录类型，不记录邮箱或验证码。
            log.error("registration verification mail failed, errorCode={}, cause={}",
                    ErrorCode.INTERNAL_ERROR, exception.getClass().getSimpleName());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private String businessId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_ID_LENGTH);
    }
}
