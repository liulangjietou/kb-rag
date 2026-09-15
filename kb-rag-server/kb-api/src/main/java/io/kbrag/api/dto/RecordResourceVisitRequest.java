package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.enums.ResourceVisitKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 只接受资源种类和业务 ID；归属与访问时间由当前会话决定。 */
public record RecordResourceVisitRequest(
        @JsonProperty("resource_type") @NotNull ResourceVisitKind kind,
        @JsonProperty("resource_id") @NotBlank @Size(max = 64) String resourceId) { }
