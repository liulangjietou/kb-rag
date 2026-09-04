package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 邮箱验证成功后仅返回一次的注册票据。
 *
 * @param registrationTicket 一次性票据明文
 * @param expiresInSeconds    有效秒数
 *
 * @author owlzhangfq@gmail.com
 */
public record VerifiedRegistrationEmailResponse(
        @JsonProperty("registration_ticket") String registrationTicket,
        @JsonProperty("expires_in_seconds") long expiresInSeconds) {
}
