package io.kbrag.app.registration;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 随机票据在 BCrypt 前进行的无锁短查询；事务内仍会二次校验。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class RegistrationSubmissionPreflight {

    private final RegistrationSubmissionLookup submissionLookup;
    private final EmailVerificationMapper emailVerificationMapper;

    /**
     * 已提交则返回原回执；尚未提交且票据看似有效则返回 null。
     *
     * @param submissionId 浏览器幂等 UUID
     * @param ticketHash 邮箱验证票据摘要
     * @return 原回执，允许继续提交时返回 {@code null}
     */
    public RegistrationSubmitted inspect(String submissionId, String ticketHash) {
        RegistrationSubmitted existing = submissionLookup.find(submissionId, ticketHash);
        if (existing != null) {
            return existing;
        }
        EmailVerification verification = emailVerificationMapper.selectByTicketHash(ticketHash);
        LocalDateTime now = LocalDateTime.now();
        if (verification == null || verification.getStatus() != EmailVerificationStatus.VERIFIED
                || verification.getTicketExpiresAt() == null
                || !verification.getTicketExpiresAt().isAfter(now)) {
            throw BizException.invalidParam("registration ticket is invalid or expired");
        }
        return null;
    }
}
