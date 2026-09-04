package io.kbrag.app.registration;

import java.time.LocalDateTime;

/**
 * 待审核注册申请的公开结果。
 *
 * @param applicationId 申请业务标识
 * @param email         已验证邮箱
 * @param status        生命周期状态
 * @param createdAt     提交时间
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationSubmitted(
        String applicationId,
        String email,
        String status,
        LocalDateTime createdAt) {
}
