package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.memory.MemoryAdminService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One console page of memory entities, the M19 contract: user ids aggregated over live nodes.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryEntityPageResponse(

        List<EntityResponse> items,

        int page,

        int size,

        long total) {

    /**
     * Maps the application page onto the transport shape.
     *
     * @param page loaded page
     * @return response body
     */
    public static MemoryEntityPageResponse from(MemoryAdminService.EntityPage page) {
        return new MemoryEntityPageResponse(
                page.items().stream().map(EntityResponse::from).toList(),
                page.page(), page.size(), page.total());
    }

    /**
     * One memory entity aggregate row.
     */
    public record EntityResponse(

            @JsonProperty("user_id")
            String userId,

            @JsonProperty("node_count")
            long nodeCount,

            @JsonProperty("updated_at")
            LocalDateTime updatedAt) {

        static EntityResponse from(MemoryAdminService.EntityView view) {
            return new EntityResponse(view.userId(), view.nodeCount(), view.updatedAt());
        }
    }
}
