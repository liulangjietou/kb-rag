package io.kbrag.app.registration;

import io.kbrag.app.auth.EmailIdentityClaimService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import io.kbrag.domain.mapper.RegistrationSubmissionClaimMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 注册最终提交的短数据库事务，不执行 BCrypt 或网络调用。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationSubmissionTransaction {

    private static final String APPLICATION_ID_PREFIX = "reg_";
    private static final int RANDOM_ID_LENGTH = 20;

    private final EmailVerificationMapper emailVerificationMapper;
    private final RegistrationApplicationMapper registrationApplicationMapper;
    private final EmailIdentityClaimService emailIdentityClaimService;
    private final RegistrationSubmissionLookup submissionLookup;
    private final RegistrationSubmissionClaimMapper submissionClaimMapper;

    /**
     * 锁定票据、保存独立申请事实并消费票据；幂等重试返回原回执。
     *
     * @param command 已在事务外完成校验和密码哈希的数据
     * @return 新申请或已提交申请的稳定回执
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public RegistrationSubmitted submit(RegistrationSubmissionCommand command) {
        claimSubmissionId(command.submissionId(), command.ticketHash());
        RegistrationSubmitted previous = submissionLookup.find(
                command.submissionId(), command.ticketHash());
        if (previous != null) {
            return previous;
        }

        EmailVerification verification =
                emailVerificationMapper.selectByTicketHashForUpdate(command.ticketHash());
        LocalDateTime now = LocalDateTime.now();
        if (!usableTicket(verification, now)) {
            // 并发重试可能在等待 ticket 行锁期间由首个请求完成；READ_COMMITTED 可读取新回执。
            previous = submissionLookup.find(command.submissionId(), command.ticketHash());
            if (previous != null) {
                return previous;
            }
            throw BizException.invalidParam("registration ticket is invalid or expired");
        }
        String email = verification.getEmail();
        if (emailIdentityClaimService.claimed(email)) {
            throw BizException.invalidParam("email is already registered");
        }

        RegistrationApplication latest = registrationApplicationMapper.selectByEmailForUpdate(email);
        if (latest != null && latest.getStatus() == RegistrationApplicationStatus.PENDING) {
            throw BizException.invalidParam("registration application is already pending review");
        }
        if (latest != null && latest.getStatus() == RegistrationApplicationStatus.APPROVED) {
            throw BizException.invalidParam("email is already registered");
        }

        RegistrationApplication application = new RegistrationApplication();
        application.setApplicationId(businessId());
        application.setEmail(email);
        application.setSubmissionId(command.submissionId());
        application.setSubmissionTicketHash(command.ticketHash());
        application.setDisplayName(command.displayName());
        application.setTeamName(command.teamName());
        application.setPasswordHash(command.passwordHash());
        application.setApplicationNote(command.applicationNote());
        application.setStatus(RegistrationApplicationStatus.PENDING);
        application.setEmailVerifiedAt(verification.getVerifiedAt());
        application.setCreatedAt(now);
        if (registrationApplicationMapper.insert(application) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册申请保存失败，请稍后重试");
        }
        if (emailVerificationMapper.consumeVerifiedTicket(
                verification.getVerificationId(), command.ticketHash(), now) != 1) {
            throw BizException.invalidParam("registration ticket has already been used");
        }
        log.info("registration application submitted, applicationId={}", application.getApplicationId());
        return submittedFrom(application);
    }

    private boolean usableTicket(EmailVerification verification, LocalDateTime now) {
        return verification != null && verification.getStatus() == EmailVerificationStatus.VERIFIED
                && verification.getTicketExpiresAt() != null
                && verification.getTicketExpiresAt().isAfter(now);
    }

    private RegistrationSubmitted submittedFrom(RegistrationApplication application) {
        return new RegistrationSubmitted(application.getApplicationId(), application.getEmail(),
                application.getStatus().name(), application.getCreatedAt());
    }

    private void claimSubmissionId(String submissionId, String ticketHash) {
        submissionClaimMapper.insertIfAbsent(submissionId, ticketHash);
        String claimedHash = submissionClaimMapper.selectTicketHashForUpdate(submissionId);
        if (claimedHash == null || !MessageDigest.isEqual(
                claimedHash.getBytes(StandardCharsets.US_ASCII),
                ticketHash.getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.invalidParam("client_submission_id has already been used");
        }
    }

    private String businessId() {
        return APPLICATION_ID_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_ID_LENGTH);
    }
}
