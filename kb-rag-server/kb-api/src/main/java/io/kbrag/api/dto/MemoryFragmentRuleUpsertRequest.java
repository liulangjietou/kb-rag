package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.MemoryExtractVersion;
import io.kbrag.domain.enums.MemoryInstructionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Memory fragment rule create/edit payload, the M19 contract.
 *
 * <p>Enum fields arrive as strings and are parsed here so an unknown literal answers a 400 with a
 * message instead of a Jackson binding stack trace; the numeric/semantic checks (expire days,
 * CUSTOM requires an instruction) stay in the service where they also guard non-HTTP callers.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class MemoryFragmentRuleUpsertRequest {

    /** Rule display name, unique inside the library. */
    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 最长 64 字符")
    private String name;

    /** DEFAULT or CUSTOM. */
    @JsonProperty("instruction_type")
    @NotBlank(message = "instruction_type 不能为空")
    private String instructionType;

    /** Custom extraction instruction, required when the type is CUSTOM. */
    @Size(max = 2000, message = "instruction 最长 2000 字符")
    private String instruction;

    /** Whether extraction may merge and update old memories; the contract default is on. */
    @JsonProperty("auto_update")
    private Boolean autoUpdate = Boolean.TRUE;

    /** Lifetime in days, 7/30/180 or {@code null} for never expiring. */
    @JsonProperty("expire_days")
    private Integer expireDays;

    /** PRO or LITE, {@code null} takes the service default. */
    @JsonProperty("extract_version")
    private String extractVersion;

    /**
     * Converts to the application command, rejecting unknown enum literals.
     *
     * @return command
     */
    public MemoryAdminService.FragmentRuleCommand toCommand() {
        return new MemoryAdminService.FragmentRuleCommand(name, parseInstructionType(),
                instruction, !Boolean.FALSE.equals(autoUpdate), expireDays, parseExtractVersion());
    }

    private MemoryInstructionType parseInstructionType() {
        try {
            return MemoryInstructionType.from(instructionType);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("instruction_type 仅支持 DEFAULT 或 CUSTOM");
        }
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
}
