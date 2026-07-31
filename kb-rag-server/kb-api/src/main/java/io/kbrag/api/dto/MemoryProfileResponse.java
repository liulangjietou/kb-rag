package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryProfileService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One entity's profile under one rule as the memory open API returns it, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryProfileResponse(

        @JsonProperty("rule_id")
        String ruleId,

        @JsonProperty("rule_name")
        String ruleName,

        @JsonProperty("user_id")
        String userId,

        List<AttributeResponse> attributes,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt) {

    /**
     * Maps the application view onto the transport shape.
     *
     * @param view profile view
     * @return response body
     */
    public static MemoryProfileResponse from(MemoryProfileService.ProfileView view) {
        return new MemoryProfileResponse(view.ruleId(), view.ruleName(), view.userId(),
                view.attributes().stream()
                        .map(attribute -> new AttributeResponse(attribute.name(), attribute.value()))
                        .toList(),
                view.updatedAt());
    }

    /**
     * One attribute of the profile.
     *
     * @param name  attribute name
     * @param value current value, {@code null} when neither extracted nor given an initial value
     */
    public record AttributeResponse(String name, String value) {
    }
}
