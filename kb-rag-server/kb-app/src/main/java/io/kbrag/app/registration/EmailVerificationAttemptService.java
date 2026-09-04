package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.VerificationCodeDeliveryStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 验证码尝试的独立提交边界。
 *
 * <p>错误验证码会以业务异常结束 API 调用，但剩余次数必须先提交，不能随外层异常回滚。
 * 因此行锁、扣减与状态迁移放在独立 Spring Bean 的 REQUIRES_NEW 事务中。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailVerificationAttemptService {

    private final EmailVerificationMapper emailVerificationMapper;
    private final RegistrationHmac registrationHmac;

    /**
     * 校验一次验证码并在成功时写入票据摘要。
     *
     * @return 成功、错误或失效；调用方根据结果决定公开响应
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public VerificationAttempt verify(String email, String submittedCode, String ticketHash,
                                      LocalDateTime ticketExpiresAt, LocalDateTime now) {
        EmailVerification verification = emailVerificationMapper.selectByEmailForUpdate(email);
        if (!challengeUsable(verification, now)) {
            clearExpiredChallenge(verification, now);
            return VerificationAttempt.INVALID;
        }

        String submittedHmac = registrationHmac.verificationCode(
                verification.getVerificationId(), email, submittedCode);
        if (!constantTimeEquals(verification.getCodeHmac(), submittedHmac)) {
            int remaining = Math.max(0, verification.getAttemptsRemaining() - 1);
            verification.setAttemptsRemaining(remaining);
            if (remaining == 0) {
                verification.setCodeHmac(null);
                verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.NONE);
                if (!activeTicket(verification, now)) {
                    invalidateWithoutTicket(verification);
                }
            }
            updateOrFail(verification);
            return VerificationAttempt.INCORRECT;
        }

        verification.setStatus(EmailVerificationStatus.VERIFIED);
        verification.setCodeHmac(null);
        verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.NONE);
        verification.setAttemptsRemaining(0);
        verification.setTicketHash(ticketHash);
        verification.setTicketExpiresAt(ticketExpiresAt);
        verification.setVerifiedAt(now);
        updateOrFail(verification);
        return VerificationAttempt.VERIFIED;
    }

    private boolean challengeUsable(EmailVerification verification, LocalDateTime now) {
        return verification != null
                && (verification.getStatus() == EmailVerificationStatus.ISSUED
                || verification.getStatus() == EmailVerificationStatus.VERIFIED)
                && verification.getCodeDeliveryStatus() == VerificationCodeDeliveryStatus.DELIVERED
                && verification.getCodeHmac() != null
                && verification.getAttemptsRemaining() != null
                && verification.getAttemptsRemaining() > 0
                && verification.getExpiresAt() != null
                && verification.getExpiresAt().isAfter(now);
    }

    private void clearExpiredChallenge(EmailVerification verification, LocalDateTime now) {
        if (verification != null
                && (verification.getStatus() == EmailVerificationStatus.ISSUED
                || verification.getStatus() == EmailVerificationStatus.VERIFIED)
                && verification.getCodeHmac() != null
                && verification.getExpiresAt() != null
                && !verification.getExpiresAt().isAfter(now)) {
            verification.setCodeHmac(null);
            verification.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.NONE);
            verification.setAttemptsRemaining(0);
            if (!activeTicket(verification, now)) {
                invalidateWithoutTicket(verification);
            }
            updateOrFail(verification);
        }
    }

    private void updateOrFail(EmailVerification verification) {
        if (emailVerificationMapper.updateById(verification) != 1) {
            log.error("registration verification state update failed, errorCode={}, verificationId={}",
                    ErrorCode.INTERNAL_ERROR, verification.getVerificationId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请稍后重试");
        }
    }

    private boolean activeTicket(EmailVerification verification, LocalDateTime now) {
        return verification.getStatus() == EmailVerificationStatus.VERIFIED
                && verification.getTicketHash() != null
                && verification.getTicketExpiresAt() != null
                && verification.getTicketExpiresAt().isAfter(now)
                && verification.getConsumedAt() == null;
    }

    private void invalidateWithoutTicket(EmailVerification verification) {
        verification.setStatus(EmailVerificationStatus.INVALIDATED);
        verification.setTicketHash(null);
        verification.setTicketExpiresAt(null);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 验证尝试结果。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum VerificationAttempt {
        VERIFIED,
        INCORRECT,
        INVALID
    }
}
