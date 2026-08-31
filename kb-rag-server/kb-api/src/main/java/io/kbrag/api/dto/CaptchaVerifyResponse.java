package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录滑块验证通过后返回的一次性凭证。
 *
 * @param captchaProof     一次性凭证
 * @param expiresInSeconds 凭证有效期
 *
 * @author owlzhangfq@gmail.com
 */
public record CaptchaVerifyResponse(
        @JsonProperty("captcha_proof") String captchaProof,
        @JsonProperty("expires_in_seconds") long expiresInSeconds) {
}
