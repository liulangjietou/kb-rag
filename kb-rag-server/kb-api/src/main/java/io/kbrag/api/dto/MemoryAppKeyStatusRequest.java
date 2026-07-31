package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Memory key status payload, the M19 contract.
 *
 * @param status {@code ENABLED} or {@code DISABLED}
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryAppKeyStatusRequest(
        @NotBlank(message = "status 不能为空") String status) {
}
