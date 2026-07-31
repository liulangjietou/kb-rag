package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;
import io.kbrag.domain.entity.MemoryLibrary;

import java.time.LocalDateTime;

/**
 * Memory library card, the M19 contract: identity plus the statistics the console shows.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryLibraryResponse(

        @JsonProperty("library_id")
        String libraryId,

        String name,

        String description,

        @JsonProperty("fragment_rule_count")
        long fragmentRuleCount,

        @JsonProperty("profile_rule_count")
        long profileRuleCount,

        @JsonProperty("node_count")
        long nodeCount,

        @JsonProperty("entity_count")
        long entityCount,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt) {

    /**
     * Maps the application view onto the transport shape.
     *
     * @param view library with statistics
     * @return response body
     */
    public static MemoryLibraryResponse from(MemoryAdminService.LibraryView view) {
        MemoryLibrary library = view.library();
        return new MemoryLibraryResponse(library.getLibraryId(), library.getName(),
                library.getDescription(), view.fragmentRuleCount(), view.profileRuleCount(),
                view.nodeCount(), view.entityCount(), library.getCreatedAt(),
                library.getUpdatedAt());
    }
}
