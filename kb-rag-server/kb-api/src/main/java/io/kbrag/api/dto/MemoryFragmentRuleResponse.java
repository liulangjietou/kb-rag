package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.domain.entity.MemoryFragmentRule;

import java.time.LocalDateTime;

/**
 * Memory fragment rule view, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryFragmentRuleResponse(

        @JsonProperty("rule_id")
        String ruleId,

        @JsonProperty("library_id")
        String libraryId,

        String name,

        @JsonProperty("instruction_type")
        String instructionType,

        String instruction,

        @JsonProperty("auto_update")
        boolean autoUpdate,

        @JsonProperty("expire_days")
        Integer expireDays,

        @JsonProperty("extract_version")
        String extractVersion,

        boolean builtin,

        @JsonProperty("node_count")
        long nodeCount,

        @JsonProperty("created_at")
        LocalDateTime createdAt) {

    /**
     * Maps the application view onto the transport shape.
     *
     * @param view rule with its node count
     * @return response body
     */
    public static MemoryFragmentRuleResponse from(MemoryAdminService.FragmentRuleView view) {
        MemoryFragmentRule rule = view.rule();
        return new MemoryFragmentRuleResponse(rule.getRuleId(), rule.getLibraryId(), rule.getName(),
                rule.getInstructionType() == null ? null : rule.getInstructionType().name(),
                rule.getInstruction(),
                rule.getAutoUpdate() != null && rule.getAutoUpdate() == 1,
                rule.getExpireDays(),
                rule.getExtractVersion() == null ? null : rule.getExtractVersion().name(),
                rule.getBuiltin() != null && rule.getBuiltin() == 1,
                view.nodeCount(), rule.getCreatedAt());
    }
}
