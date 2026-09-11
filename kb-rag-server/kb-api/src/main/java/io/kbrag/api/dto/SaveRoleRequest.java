package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Role definition submitted by the console, used for both creation and update.
 *
 * <p>The grants and the scope are complete sets, not deltas, because that is what the checkbox grid in front
 * of the operator represents. The code is only read on creation; on update it is ignored, since configuration
 * and the provisioning default refer to it.
 *
 * @param code            role code, uppercased, required on creation
 * @param name            display label
 * @param description     purpose note
 * @param kbScopeAll      whether the role sees every knowledge base
 * @param kbIds           scoped knowledge bases, ignored when {@code kbScopeAll}
 * @param permissionCodes complete set of granted permission codes
 * @param appScopeAll     是否可使用本租户全部应用；省略时更新保留原范围，创建默认空范围
 * @param appIds          指定应用；提供列表时必须同时提供 appScopeAll
 *
 * @author owlzhangfq@gmail.com
 */
public record SaveRoleRequest(
        @Size(max = 64, message = "must be at most 64 characters") String code,
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String name,
        @Size(max = 255, message = "must be at most 255 characters") String description,
        @JsonProperty("kb_scope_all") boolean kbScopeAll,
        @JsonProperty("kb_ids") List<String> kbIds,
        @JsonProperty("permission_codes") List<String> permissionCodes,
        @JsonProperty("app_scope_all") Boolean appScopeAll,
        @JsonProperty("app_ids") @Size(max = 2000)
        List<@NotBlank @Size(max = 64) String> appIds) {

    /** 旧客户端可整体省略应用范围；显式提交应用列表必须同时声明范围模式。 */
    @JsonIgnore
    @AssertTrue(message = "app_scope_all is required when app_ids is provided")
    public boolean isAppScopeComplete() {
        return appIds == null || appScopeAll != null;
    }
}
