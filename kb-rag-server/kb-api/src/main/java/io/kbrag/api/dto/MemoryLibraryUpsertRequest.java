package io.kbrag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Memory library create/edit payload, the M19 contract.
 *
 * @param name        display name, unique among live libraries
 * @param description optional description, may guide the calling agent
 *
 * @author owlzhangfq@gmail.com
 */
public record MemoryLibraryUpsertRequest(
        @NotBlank(message = "name 不能为空") @Size(max = 64, message = "name 最长 64 字符")
        String name,
        @Size(max = 512, message = "description 最长 512 字符")
        String description) {
}
