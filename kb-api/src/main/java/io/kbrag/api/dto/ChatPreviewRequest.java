package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Console chat preview payload: the open chat payload without {@code app_id}, which the path carries, plus the
 * version to preview.
 *
 * <p>A subclass rather than a copy of the eight shared fields: a preview <em>is</em> a knowledge call, and two
 * declarations of the same payload would eventually disagree on which overrides exist - the exact drift the
 * white list rule is meant to prevent.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public class ChatPreviewRequest extends KnowledgeCallRequest {

    /**
     * Version to preview by business id, {@code null} takes the newest version of the application.
     *
     * <p>By id and not by literal, unlike the open API: a preview may target a draft, and a draft has no
     * meaningful public version literal yet.
     */
    @JsonProperty("app_version_id")
    private String appVersionId;
}
