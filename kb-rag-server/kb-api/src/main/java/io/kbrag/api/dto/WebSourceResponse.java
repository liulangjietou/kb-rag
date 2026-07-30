package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.entity.WebSource;

import java.time.LocalDateTime;

/**
 * Web source registration view.
 *
 * @param sourceId        business identifier
 * @param kbId            knowledge base the fetched pages land in
 * @param url             registered page address
 * @param docId           document the fetches feed, {@code null} until the first successful fetch
 * @param fileName        derived file name the document carries, {@code null} until the first fetch
 * @param syncEnabled     whether the scheduled pass includes this source
 * @param renderJs        whether this source is fetched through the headless browser, the M17 switch
 * @param lastFetchStatus outcome of the last sync attempt, {@code null} before the first one
 * @param lastFetchAt     ISO instant of the last sync attempt
 * @param lastError       why the last sync failed or was skipped, {@code null} on success
 * @param createdAt       ISO registration timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record WebSourceResponse(
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("kb_id") String kbId,
        String url,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("sync_enabled") boolean syncEnabled,
        @JsonProperty("render_js") boolean renderJs,
        @JsonProperty("last_fetch_status") String lastFetchStatus,
        @JsonProperty("last_fetch_at") String lastFetchAt,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("created_at") String createdAt) {

    private static final int SYNC_ON = 1;
    private static final int RENDER_ON = 1;

    /**
     * Maps an entity onto its view.
     *
     * @param entity registration entity
     * @return view
     */
    public static WebSourceResponse from(WebSource entity) {
        return new WebSourceResponse(
                entity.getSourceId(),
                entity.getKbId(),
                entity.getUrl(),
                entity.getDocId(),
                entity.getFileName(),
                entity.getSyncEnabled() != null && entity.getSyncEnabled() == SYNC_ON,
                entity.getRenderJs() != null && entity.getRenderJs() == RENDER_ON,
                entity.getLastFetchStatus() == null ? null : entity.getLastFetchStatus().name(),
                iso(entity.getLastFetchAt()),
                entity.getLastError(),
                iso(entity.getCreatedAt()));
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
