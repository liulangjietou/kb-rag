package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 邮箱验证码校验请求。
 *
 * @param email 已申请验证码的邮箱
 * @param code  六位数字验证码
 *
 * @author owlzhangfq@gmail.com
 */
public record VerifyRegistrationEmailRequest(
        @NotBlank String email,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
}
