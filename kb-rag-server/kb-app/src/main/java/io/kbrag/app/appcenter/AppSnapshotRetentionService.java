package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.index.IndexSnapshotService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retires the index snapshots of application versions that fell outside the retention window, requirement
 * section 4.7 "storage cost doubles".
 *
 * <p><b>Why a retention window exists at all.</b> A snapshot is a full copy of the corpus of every knowledge
 * base the version links, so the disk a deployment needs grows linearly with its release count. Keeping the
 * last few retired releases buys the rollback depth the requirement promises; keeping all of them buys nothing
 * and eventually fills the volume.
 *
 * <p><b>The released version is never touched</b>, whatever the window says. It is the version serving
 * production traffic out of that very snapshot, and it is not counted against the window either - the window
 * measures how far back a rollback can reach, so counting the current release would silently shorten it by one.
 *
 * <p><b>The order of the three steps matters.</b> Indices go first, the registry row second, the columns last.
 * Clearing the columns first would release the archiving pin while the snapshot still exists, opening a window
 * in which the document version retention could archive chunks a live snapshot still points at - the exact
 * corruption the pin exists to prevent.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSnapshotRetentionService {

    private final AppVersionMapper appVersionMapper;
    private final IndexSnapshotService indexSnapshotService;
    private final KbProperties properties;

    /**
     * Runs one retention pass over every application.
     */
    @Scheduled(cron = "${kb.app.snapshot-cleanup-cron:0 15 4 * * *}")
    public void scheduledPass() {
        if (!properties.getApp().isSnapshotCleanupEnabled()) {
            return;
        }
        try {
            int retired = retireExpired();
            if (retired > 0) {
                log.info("application snapshot retention pass finished, retired={}", retired);
            }
        } catch (Exception e) {
            log.error("application snapshot retention pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Retires the snapshots outside the retention window of every application.
     *
     * @return number of application versions whose snapshot was retired
     */
    public int retireExpired() {
        List<AppVersion> candidates = appVersionMapper.selectList(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getStatus, AppVersionStatus.SUPERSEDED)
                .isNotNull(AppVersion::getIndexSnapshots));
        if (CollectionUtils.isEmpty(candidates)) {
            return 0;
        }
        int retainCount = properties.getApp().resolvedSnapshotRetainCount();
        int retired = 0;
        for (Map.Entry<String, List<AppVersion>> entry : byApp(candidates).entrySet()) {
            for (AppVersion expired : expiredOf(entry.getValue(), retainCount)) {
                retire(expired);
                retired++;
            }
        }
        return retired;
    }

    /**
     * Retired versions of one application that lost their snapshot, newest first inside each group.
     *
     * @param versions    retired versions of one application that still hold a snapshot
     * @param retainCount snapshots kept per application
     * @return versions whose snapshot is to be dropped
     */
    private List<AppVersion> expiredOf(List<AppVersion> versions, int retainCount) {
        if (versions.size() <= retainCount) {
            return List.of();
        }
        List<AppVersion> ordered = new ArrayList<>(versions);
        // Ordered by the release moment rather than by creation: a rollback re-releases an older version, and
        // what a rollback depth means is "how recently was this serving", not "how recently was it configured".
        ordered.sort(Comparator.comparing(AppVersion::getReleasedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(AppVersion::getId, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        return ordered.subList(retainCount, ordered.size());
    }

    /**
     * Drops the snapshot of one version and releases the archiving pin it held.
     *
     * @param version retired version whose snapshot expired
     */
    private void retire(AppVersion version) {
        indexSnapshotService.drop(version.indexSnapshotList());
        appVersionMapper.update(null, new LambdaUpdateWrapper<AppVersion>()
                .set(AppVersion::getIndexSnapshots, null)
                .set(AppVersion::getVisibleVersionIds, null)
                .eq(AppVersion::getAppVersionId, version.getAppVersionId()));
        log.info("application version snapshot retired, appVersionId={}, indexes={}",
                version.getAppVersionId(), version.indexSnapshotList().size());
    }

    private Map<String, List<AppVersion>> byApp(List<AppVersion> versions) {
        Map<String, List<AppVersion>> byApp = new LinkedHashMap<>();
        for (AppVersion version : versions) {
            byApp.computeIfAbsent(version.getAppId(), key -> new ArrayList<>()).add(version);
        }
        return byApp;
    }
}
