package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Complete new set of roles held by an account.
 *
 * <p>A replacement rather than an add and a remove: the console shows the whole set, so submitting the whole
 * set is what makes the screen and the stored state agree even when two operators edit at once.
 *
 * @param roleIds role business ids, empty revokes everything
 *
 * @author owlzhangfq@gmail.com
 */
public record AssignRolesRequest(
        @JsonProperty("role_ids") List<String> roleIds) {
}
