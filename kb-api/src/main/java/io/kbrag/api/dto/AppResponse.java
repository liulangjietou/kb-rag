package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;

/**
 * Application payload.
 *
 * <p>The released version travels with it because the list page's only interesting question about an
 * application is whether it currently serves traffic and with which version; resolving that per row on the
 * client would be one request per application.
 *
 * @param appId             business identifier
 * @param name              display name
 * @param description       free text description
 * @param releasedVersion   version literal currently serving the open API, {@code null} when none
 * @param releasedVersionId version business id currently serving the open API, {@code null} when none
 * @param createdAt         ISO creation timestamp
 * @param updatedAt         ISO update timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record AppResponse(
        @JsonProperty("app_id") String appId,
        String name,
        String description,
        @JsonProperty("released_version") String releasedVersion,
        @JsonProperty("released_version_id") String releasedVersionId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    /**
     * Maps a stored application onto its response.
     *
     * @param app      stored application
     * @param released currently released version, {@code null} when none
     * @return response
     */
    public static AppResponse from(App app, AppVersion released) {
        return new AppResponse(
                app.getAppId(),
                app.getName(),
                app.getDescription(),
                released == null ? null : released.getVersion(),
                released == null ? null : released.getAppVersionId(),
                app.getCreatedAt() == null ? null : app.getCreatedAt().toString(),
                app.getUpdatedAt() == null ? null : app.getUpdatedAt().toString());
    }
}
