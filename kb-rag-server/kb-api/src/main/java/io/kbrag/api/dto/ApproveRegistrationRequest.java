package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理员通过申请时原子分配租户与角色。
 *
 * @param tenantId 目标租户
 * @param roleIds  至少一个该租户角色
 *
 * @author owlzhangfq@gmail.com
 */
public record ApproveRegistrationRequest(
        @NotBlank @Size(max = 64)
        @JsonProperty("tenant_id") String tenantId,
        @Valid @NotEmpty @Size(max = 50)
        @JsonProperty("role_ids") List<@NotBlank @Size(max = 64) String> roleIds) {
}
