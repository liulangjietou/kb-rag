package io.kbrag.app.registration;

import io.kbrag.app.auth.EmailIdentityClaimService;
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
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 注册验证码状态决策的短事务边界。
 *
 * <p>本事务只完成全局邮箱身份查询、单邮箱行锁与 challenge 轮换，不执行 SMTP。调用返回后事务
 * 已提交，外层才同步发信，避免网络等待长期占用数据库连接。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationIssuanceService {

    private final EmailVerificationMapper emailVerificationMapper;
    private final EmailIdentityClaimService emailIdentityClaimService;

    /**
     * 原子决定新建、轮换或复用当前 challenge。
     *
     * @param candidate 只含 HMAC 的新 challenge 候选
     * @param now       外层统一时钟
     * @return 非敏感状态决策
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public VerificationIssuanceDecision prepare(EmailVerification candidate,
                                                LocalDateTime now) {
        boolean occupied = emailIdentityClaimService.claimed(candidate.getEmail());
        emailVerificationMapper.insertIfAbsent(candidate);
        EmailVerification verification =
                emailVerificationMapper.selectByEmailForUpdate(candidate.getEmail());
        if (verification == null) {
            // 随机 verification_id 与另一邮箱碰撞时，不能发送一个数据库未保存的验证码。
            log.error("registration verification initialization conflict, errorCode={}",
                    ErrorCode.INTERNAL_ERROR);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请稍后重试");
        }
        boolean candidatePersisted = candidateMatches(verification, candidate);
        boolean activeTicket = activeTicket(verification, now);
        if (!candidatePersisted && deliveryInProgress(verification, now)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码邮件正在发送，请稍后重试");
        }
        if (!candidatePersisted && reusableChallenge(verification, now)
                && verification.getResendAvailableAt() != null
                && verification.getResendAvailableAt().isAfter(now)) {
            return new VerificationIssuanceDecision(occupied, false,
                    remainingResendSeconds(now, verification.getResendAvailableAt()));
        }
        if (!candidatePersisted) {
            rotateChallengeState(verification, candidate, activeTicket);
            if (emailVerificationMapper.updateById(verification) != 1) {
                log.error("registration verification rotation conflict, errorCode={}",
                        ErrorCode.INTERNAL_ERROR);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请稍后重试");
            }
        }
        return new VerificationIssuanceDecision(occupied, true,
                Duration.between(now, candidate.getResendAvailableAt()).toSeconds());
    }

    private boolean reusableChallenge(EmailVerification verification, LocalDateTime now) {
        return (verification.getStatus() == EmailVerificationStatus.ISSUED
                || verification.getStatus() == EmailVerificationStatus.VERIFIED)
                && verification.getCodeDeliveryStatus() == VerificationCodeDeliveryStatus.DELIVERED
                && verification.getCodeHmac() != null
                && verification.getExpiresAt() != null
                && verification.getExpiresAt().isAfter(now);
    }

    private boolean deliveryInProgress(EmailVerification verification, LocalDateTime now) {
        return verification.getCodeDeliveryStatus() == VerificationCodeDeliveryStatus.ISSUING
                && verification.getCodeHmac() != null
                && verification.getExpiresAt() != null
                && verification.getExpiresAt().isAfter(now)
                && verification.getResendAvailableAt() != null
                && verification.getResendAvailableAt().isAfter(now);
    }

    private boolean activeTicket(EmailVerification verification, LocalDateTime now) {
        return verification.getStatus() == EmailVerificationStatus.VERIFIED
                && verification.getTicketHash() != null
                && verification.getTicketExpiresAt() != null
                && verification.getTicketExpiresAt().isAfter(now)
                && verification.getConsumedAt() == null;
    }

    private void rotateChallengeState(EmailVerification current, EmailVerification candidate,
                                      boolean preserveActiveTicket) {
        // challenge 与 ticket 正交：匿名发码只能轮换验证码，不能撤销有效旧票据。
        current.setVerificationId(candidate.getVerificationId());
        current.setCodeHmac(candidate.getCodeHmac());
        current.setCodeDeliveryStatus(VerificationCodeDeliveryStatus.ISSUING);
        current.setStatus(preserveActiveTicket
                ? EmailVerificationStatus.VERIFIED : EmailVerificationStatus.ISSUED);
        current.setAttemptsRemaining(candidate.getAttemptsRemaining());
        current.setExpiresAt(candidate.getExpiresAt());
        current.setResendAvailableAt(candidate.getResendAvailableAt());
        if (!preserveActiveTicket) {
            current.setTicketHash(null);
            current.setTicketExpiresAt(null);
            current.setVerifiedAt(null);
            current.setConsumedAt(null);
        }
        current.setRequestIpHash(candidate.getRequestIpHash());
    }

    private long remainingResendSeconds(LocalDateTime now, LocalDateTime resendAvailableAt) {
        long remainingMillis = Duration.between(now, resendAvailableAt).toMillis();
        return Math.max(1L, (remainingMillis + 999L) / 1_000L);
    }

    private boolean candidateMatches(EmailVerification persisted, EmailVerification candidate) {
        if (!candidate.getVerificationId().equals(persisted.getVerificationId())
                || persisted.getCodeHmac() == null) {
            return false;
        }
        return MessageDigest.isEqual(candidate.getCodeHmac().getBytes(StandardCharsets.US_ASCII),
                persisted.getCodeHmac().getBytes(StandardCharsets.US_ASCII));
    }
}
