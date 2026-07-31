package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.MemoryNode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One memory node as the memory open API returns it, the M19 contract.
 *
 * <p>The stored metadata JSON is parsed back into an object here, so the caller receives exactly
 * the shape it sent - "stored and returned verbatim" is the metadata contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryNodeResponse(

        @JsonProperty("memory_node_id")
        String memoryNodeId,

        @JsonProperty("library_id")
        String libraryId,

        @JsonProperty("rule_id")
        String ruleId,

        @JsonProperty("user_id")
        String userId,

        String content,

        String source,

        @JsonProperty("meta_data")
        Map<String, Object> metaData,

        @JsonProperty("expire_at")
        LocalDateTime expireAt,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        Double score) {

    /**
     * Maps a stored row onto the transport shape without a score.
     *
     * @param node stored row
     * @return response body
     */
    public static MemoryNodeResponse from(MemoryNode node) {
        return from(node, null);
    }

    /**
     * Maps a stored row onto the transport shape.
     *
     * @param node  stored row
     * @param score relevance score of a search hit, {@code null} outside search
     * @return response body
     */
    public static MemoryNodeResponse from(MemoryNode node, Double score) {
        return new MemoryNodeResponse(node.getNodeId(), node.getLibraryId(), node.getRuleId(),
                node.getUserId(), node.getContent(),
                node.getSource() == null ? null : node.getSource().name(),
                parseMetaData(node.getMetaData()), node.getExpireAt(),
                node.getCreatedAt(), node.getUpdatedAt(), score);
    }

    private static Map<String, Object> parseMetaData(String metaData) {
        if (metaData == null || metaData.isBlank()) {
            return null;
        }
        return JsonUtil.parse(metaData, new TypeReference<Map<String, Object>>() {
        });
    }
}
