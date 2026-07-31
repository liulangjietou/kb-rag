package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.domain.entity.MemoryProfileRule;
import io.kbrag.domain.model.MemoryProfileField;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User profile rule view, the M19 contract: the rule with its parsed field definitions.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryProfileRuleResponse(

        @JsonProperty("rule_id")
        String ruleId,

        @JsonProperty("library_id")
        String libraryId,

        String name,

        @JsonProperty("extract_version")
        String extractVersion,

        List<MemoryProfileField> fields,

        @JsonProperty("created_at")
        LocalDateTime createdAt) {

    /**
     * Maps the application view onto the transport shape.
     *
     * @param view rule with parsed fields
     * @return response body
     */
    public static MemoryProfileRuleResponse from(MemoryAdminService.ProfileRuleView view) {
        MemoryProfileRule rule = view.rule();
        return new MemoryProfileRuleResponse(rule.getRuleId(), rule.getLibraryId(), rule.getName(),
                rule.getExtractVersion() == null ? null : rule.getExtractVersion().name(),
                view.fields(), rule.getCreatedAt());
    }
}
