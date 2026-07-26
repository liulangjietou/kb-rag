package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * API key authorisation scope payload.
 *
 * @param appScope allowed application ids; an empty list authorises every application
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateApiKeyScopeRequest(@JsonProperty("app_scope") List<String> appScope) {
}
