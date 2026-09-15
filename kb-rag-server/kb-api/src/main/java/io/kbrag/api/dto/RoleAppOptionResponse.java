package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 角色授权选择器的最小应用信息，不包含提示词、模型或版本配置。 */
public record RoleAppOptionResponse(@JsonProperty("app_id") String appId, String name) {
}
