package io.kbrag.app.registration;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 读取并校验一次提交的幂等回执。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class RegistrationSubmissionLookup {

    private final RegistrationApplicationMapper registrationApplicationMapper;

    /**
     * 不存在返回 null；存在但票据不匹配则拒绝复用该幂等标识。
     *
     * @param submissionId 浏览器为本次提交生成的稳定 UUID
     * @param ticketHash 邮箱验证票据摘要
     * @return 原回执，尚未提交时返回 {@code null}
     */
    public RegistrationSubmitted find(String submissionId, String ticketHash) {
        RegistrationApplication application =
                registrationApplicationMapper.selectBySubmissionId(submissionId);
        if (application == null) {
            return null;
        }
        String expected = application.getSubmissionTicketHash();
        if (expected == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                ticketHash.getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.invalidParam("client_submission_id has already been used");
        }
        return new RegistrationSubmitted(application.getApplicationId(), application.getEmail(),
                application.getStatus().name(), application.getCreatedAt());
    }
}
