package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.MemoryExtractVersion;
import io.kbrag.domain.model.MemoryProfileField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * User profile rule create/edit payload, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class MemoryProfileRuleUpsertRequest {

    /** Rule display name, unique inside the library. */
    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 最长 64 字符")
    private String name;

    /** PRO or LITE, {@code null} takes the service default. */
    @JsonProperty("extract_version")
    private String extractVersion;

    /** Field definitions, 1 to 50; name uniqueness is checked in the service. */
    @Valid
    @NotEmpty(message = "fields 至少定义 1 个")
    @Size(max = 50, message = "fields 最多定义 50 个")
    private List<FieldRequest> fields;

    /**
     * Converts to the application command, rejecting unknown enum literals.
     *
     * @return command
     */
    public MemoryAdminService.ProfileRuleCommand toCommand() {
        return new MemoryAdminService.ProfileRuleCommand(name, parseExtractVersion(),
                fields.stream().map(FieldRequest::toField).toList());
    }

    private MemoryExtractVersion parseExtractVersion() {
        if (extractVersion == null || extractVersion.isBlank()) {
            return null;
        }
        try {
            return MemoryExtractVersion.from(extractVersion);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("extract_version 仅支持 PRO 或 LITE");
        }
    }

    /**
     * One profile field definition.
     */
    @Getter
    @Setter
    public static class FieldRequest {

        /** Field name, unique inside the rule. */
        @NotBlank(message = "字段 name 不能为空")
        @Size(max = 64, message = "字段 name 最长 64 字符")
        private String name;

        /** What the field means, guides the model during extraction. */
        @Size(max = 512, message = "字段 description 最长 512 字符")
        private String description;

        /** Value served before anything has been extracted. */
        @JsonProperty("initial_value")
        @Size(max = 512, message = "字段 initial_value 最长 512 字符")
        private String initialValue;

        MemoryProfileField toField() {
            return new MemoryProfileField(name.trim(), description, initialValue);
        }
    }
}
