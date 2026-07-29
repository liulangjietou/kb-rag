package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.Permission;

/**
 * One entry of the permission catalogue the role editor renders.
 *
 * @param code       permission code
 * @param name       display label
 * @param module     grouping key
 * @param moduleName display label of the group
 *
 * @author owlzhangfq@gmail.com
 */
public record PermissionResponse(
        String code,
        String name,
        String module,
        @JsonProperty("module_name") String moduleName) {

    /**
     * Maps one catalogue row onto the transport shape.
     *
     * @param permission catalogue row
     * @return permission entry
     */
    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(
                permission.getCode(),
                permission.getName(),
                permission.getModule(),
                permission.getModuleName());
    }
}
