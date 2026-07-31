package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Memory key issue payload, the M19 contract.
 *
 * @param name     purpose note, e.g. the consuming agent's name
 * @param qpsLimit token bucket rate, {@code null} takes the deployment default
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryAppKeyCreateRequest(
        @NotBlank(message = "name 不能为空") @Size(max = 64, message = "name 最长 64 字符")
        String name,
        @JsonProperty("qps_limit")
        @Min(value = 1, message = "qps_limit 最小为 1") @Max(value = 1000, message = "qps_limit 最大为 1000")
        Integer qpsLimit) {
}
