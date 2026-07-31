package io.kbrag.api.dto;

import io.kbrag.app.memory.MemoryAdminService;

import java.util.List;

/**
 * One console page of memory libraries, the M19 contract.
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryLibraryPageResponse(

        List<MemoryLibraryResponse> items,

        int page,

        int size,

        long total) {

    /**
     * Maps the application page onto the transport shape.
     *
     * @param page loaded page
     * @return response body
     */
    public static MemoryLibraryPageResponse from(MemoryAdminService.LibraryPage page) {
        return new MemoryLibraryPageResponse(
                page.items().stream().map(MemoryLibraryResponse::from).toList(),
                page.page(), page.size(), page.total());
    }
}
