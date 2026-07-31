package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.kbrag.app.memory.MemoryAdminService;

import java.util.List;

/**
 * Memory library detail, the M19 contract: the card fields plus both rule lists.
 *
 * <p>{@code @JsonUnwrapped} flattens the card into this object so the detail is a strict superset
 * of the list item, exactly what the contract's {@code allOf} says.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryLibraryDetailResponse(

        @JsonUnwrapped
        MemoryLibraryResponse library,

        @JsonProperty("fragment_rules")
        List<MemoryFragmentRuleResponse> fragmentRules,

        @JsonProperty("profile_rules")
        List<MemoryProfileRuleResponse> profileRules) {

    /**
     * Maps the application detail onto the transport shape.
     *
     * @param detail library detail
     * @return response body
     */
    public static MemoryLibraryDetailResponse from(MemoryAdminService.LibraryDetail detail) {
        return new MemoryLibraryDetailResponse(MemoryLibraryResponse.from(detail.view()),
                detail.fragmentRules().stream().map(MemoryFragmentRuleResponse::from).toList(),
                detail.profileRules().stream().map(MemoryProfileRuleResponse::from).toList());
    }
}
