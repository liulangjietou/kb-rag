package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册验证码申请。
 *
 * @param email        待验证邮箱
 * @param captchaProof 已完成滑块后的一次性 proof
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationVerificationCodeRequest(
        @NotBlank String email,
        @NotBlank @Size(min = 43, max = 43)
        @JsonProperty("captcha_proof") String captchaProof) {
}
