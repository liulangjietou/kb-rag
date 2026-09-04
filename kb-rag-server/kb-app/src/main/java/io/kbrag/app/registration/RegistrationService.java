package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 一次性邮箱票据到待审核申请的非事务入口。
 *
 * <p>参数校验和 BCrypt 在进入数据库事务前完成，避免匿名慢哈希占用连接池；真正的票据锁定、
 * 幂等恢复、申请保存和票据消费由 {@link RegistrationSubmissionTransaction} 原子完成。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final int TICKET_LENGTH = 43;

    private final RegistrationProperties properties;
    private final RegistrationRateLimiter rateLimiter;
    private final RegistrationSubmissionPreflight submissionPreflight;
    private final RegistrationPasswordHasher passwordHasher;
    private final RegistrationSubmissionTransaction submissionTransaction;

    /**
     * 提交待审核注册申请。
     *
     * @param registrationTicket 邮箱校验成功后仅返回一次的票据
     * @param clientSubmissionId 浏览器为本次提交生成的 UUID 幂等标识
     * @param displayName        显示名称
     * @param teamName           团队名称，可空
     * @param password           强密码明文，只在当前调用栈中存在
     * @param applicationNote    申请说明，可空
     * @param clientIp           可信边界解析后的客户端地址
     * @return 待审核申请
     */
    public RegistrationSubmitted submit(String registrationTicket, String clientSubmissionId,
                                        String displayName,
                                        String teamName, String password, String applicationNote,
                                        String clientIp) {
        if (!properties.isEnabled()) {
            log.error("registration submission disabled, errorCode={}", ErrorCode.INTERNAL_ERROR);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册服务暂不可用，请联系管理员");
        }
        rateLimiter.acquireSubmissionAttempt(clientIp);
        PasswordPolicy.requireStrong(password);
        String submissionId = normalizeSubmissionId(clientSubmissionId);
        String ticketHash = hashTicket(registrationTicket);
        String normalizedDisplayName = requiredTrimmed(displayName, "display_name is required");
        String normalizedTeamName = optionalTrimmed(teamName);
        String normalizedApplicationNote = optionalTrimmed(applicationNote);

        RegistrationSubmitted existing = submissionPreflight.inspect(submissionId, ticketHash);
        if (existing != null) {
            return existing;
        }
        // 预检连接已释放；当前 Bean 无事务，慢哈希完成后才调用另一个 Bean 开启短事务。
        String passwordHash = passwordHasher.hash(password);
        return submissionTransaction.submit(new RegistrationSubmissionCommand(
                ticketHash, submissionId, normalizedDisplayName, normalizedTeamName,
                passwordHash, normalizedApplicationNote));
    }

    private String hashTicket(String ticket) {
        if (ticket == null || ticket.length() != TICKET_LENGTH) {
            throw BizException.invalidParam("registration ticket is invalid or expired");
        }
        for (int i = 0; i < ticket.length(); i++) {
            char character = ticket.charAt(i);
            boolean base64UrlCharacter = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_';
            if (!base64UrlCharacter) {
                throw BizException.invalidParam("registration ticket is invalid or expired");
            }
        }
        return HashUtil.sha256Hex(ticket);
    }

    private String normalizeSubmissionId(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.invalidParam("client_submission_id is required");
        }
        String candidate = value.trim();
        try {
            UUID parsed = UUID.fromString(candidate);
            if (!parsed.toString().equalsIgnoreCase(candidate)) {
                throw BizException.invalidParam("client_submission_id is invalid");
            }
            return parsed.toString();
        } catch (IllegalArgumentException invalid) {
            throw BizException.invalidParam("client_submission_id is invalid");
        }
    }

    private String requiredTrimmed(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BizException.invalidParam(message);
        }
        return value.trim();
    }

    private String optionalTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
