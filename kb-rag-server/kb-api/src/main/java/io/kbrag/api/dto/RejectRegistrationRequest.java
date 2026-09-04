package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员拒绝申请。
 *
 * @param reason 必填拒绝原因
 *
 * @author owlzhangfq@gmail.com
 */
public record RejectRegistrationRequest(@NotBlank @Size(max = 500) String reason) {
}
