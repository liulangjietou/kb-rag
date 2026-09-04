package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 发码公开响应。
 *
 * @param resendAfterSeconds 可再次申请前的等待秒数
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationVerificationCodeResponse(
        @JsonProperty("resend_after_seconds") long resendAfterSeconds) {
}
