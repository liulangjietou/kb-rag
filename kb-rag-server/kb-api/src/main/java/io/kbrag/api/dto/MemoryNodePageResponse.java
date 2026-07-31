package io.kbrag.api.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.MemoryNode;

import java.util.List;

/**
 * ListMemory response body, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryNodePageResponse(

        @JsonProperty("memory_nodes")
        List<MemoryNodeResponse> memoryNodes,

        int page,

        int size,

        long total) {

    /**
     * Maps a MyBatis page onto the transport shape.
     *
     * @param page loaded page
     * @return response body
     */
    public static MemoryNodePageResponse from(Page<MemoryNode> page) {
        return new MemoryNodePageResponse(
                page.getRecords().stream().map(MemoryNodeResponse::from).toList(),
                (int) page.getCurrent(), (int) page.getSize(), page.getTotal());
    }
}
