package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.registration.RegistrationSubmitted;

/**
 * 待审核申请提交结果。
 *
 * @param applicationId 申请标识
 * @param email         已验证邮箱
 * @param status        PENDING
 * @param createdAt     提交时间
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationSubmittedResponse(
        @JsonProperty("application_id") String applicationId,
        String email,
        String status,
        @JsonProperty("created_at") String createdAt) {

    public static RegistrationSubmittedResponse from(RegistrationSubmitted submitted) {
        return new RegistrationSubmittedResponse(submitted.applicationId(), submitted.email(),
                submitted.status(), submitted.createdAt() == null ? null : submitted.createdAt().toString());
    }
}
