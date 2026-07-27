package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.GateReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Application version payload, including the gate outcome the console renders.
 *
 * @param appVersionId      business identifier
 * @param appId             owning application
 * @param version           display version literal
 * @param status            release state machine position
 * @param config            configuration snapshot
 * @param gateDatasetId     baseline evaluation data set, {@code null} skips the gate
 * @param gateRunIds        dual run ids, candidate first and baseline second
 * @param gateVerdict       three state gate outcome, {@code null} until a gate ran
 * @param gateReason        classified reason literal
 * @param gateReasonMessage operator facing explanation of {@code gateReason}
 * @param gateReport        intersection metrics of both sides plus the tolerance
 * @param forceReleased     {@code true} when an operator released despite a non passing verdict
 * @param forceOperator     who forced the release
 * @param changelog         version description
 * @param releasedAt        ISO timestamp this version last became the released one
 * @param indexSnapshots    physical indices this release froze, requirement section 4.7; empty for a version
 *                          that was never released, for one released before index snapshots existed, and for one
 *                          whose snapshot the retention pass retired - all three are served from the live alias
 * @param visibleVersionKbCount document versions frozen per knowledge base, the count only
 * @param createdAt         ISO creation timestamp
 * @param updatedAt         ISO update timestamp
 *
 * @author owlzhangfq@gmail.com
 */
public record AppVersionResponse(
        @JsonProperty("app_version_id") String appVersionId,
        @JsonProperty("app_id") String appId,
        String version,
        String status,
        AppConfigSnapshot config,
        @JsonProperty("gate_dataset_id") String gateDatasetId,
        @JsonProperty("gate_run_ids") List<String> gateRunIds,
        @JsonProperty("gate_verdict") String gateVerdict,
        @JsonProperty("gate_reason") String gateReason,
        @JsonProperty("gate_reason_message") String gateReasonMessage,
        @JsonProperty("gate_report") GateReport gateReport,
        @JsonProperty("force_released") boolean forceReleased,
        @JsonProperty("force_operator") String forceOperator,
        String changelog,
        @JsonProperty("released_at") String releasedAt,
        @JsonProperty("index_snapshots") List<AppIndexSnapshot> indexSnapshots,
        @JsonProperty("visible_version_kb_count") List<VisibleVersionCountResponse> visibleVersionKbCount,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    /**
     * Maps a stored version onto its response.
     *
     * <p>Both snapshot fields go through the entity's own compatibility read, so the console and the retrieval
     * path answer "does this version have a snapshot" out of the same code.
     *
     * @param version stored version
     * @return response
     */
    public static AppVersionResponse from(AppVersion version) {
        return new AppVersionResponse(
                version.getAppVersionId(),
                version.getAppId(),
                version.getVersion(),
                version.getStatus() == null ? null : version.getStatus().name(),
                JsonUtil.parse(version.getConfig(), AppConfigSnapshot.class),
                version.getGateDatasetId(),
                version.getGateRunIds() == null ? List.of()
                        : JsonUtil.parse(version.getGateRunIds(), new TypeReference<List<String>>() {
                        }),
                version.getGateVerdict() == null ? null : version.getGateVerdict().name(),
                version.getGateReason() == null ? null : version.getGateReason().name(),
                version.getGateReason() == null ? null : version.getGateReason().message(),
                version.getGateReport() == null ? null
                        : JsonUtil.parse(version.getGateReport(), GateReport.class),
                version.forced(),
                version.getForceOperator(),
                version.getChangelog(),
                version.getReleasedAt() == null ? null : version.getReleasedAt().toString(),
                version.indexSnapshotList(),
                visibleVersionCounts(version),
                version.getCreatedAt() == null ? null : version.getCreatedAt().toString(),
                version.getUpdatedAt() == null ? null : version.getUpdatedAt().toString());
    }

    /**
     * Summarises the frozen visibility set as one count per knowledge base.
     *
     * @param version stored version
     * @return counts in the order the set was frozen, empty when nothing is frozen
     */
    private static List<VisibleVersionCountResponse> visibleVersionCounts(AppVersion version) {
        Map<String, List<String>> frozen = version.visibleVersionIdMap();
        if (frozen.isEmpty()) {
            return List.of();
        }
        List<VisibleVersionCountResponse> counts = new ArrayList<>(frozen.size());
        frozen.forEach((kbId, versionIds) -> counts.add(
                new VisibleVersionCountResponse(kbId, versionIds == null ? 0 : versionIds.size())));
        return counts;
    }
}
