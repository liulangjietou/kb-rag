package io.kbrag.app.appcenter;

import io.kbrag.app.index.IndexSnapshotService;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.AppIndexSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the snapshot retention window of requirement section 4.7: the configured number of retired releases keep
 * their snapshots, older ones lose their indices and their frozen columns, and the released version is never a
 * candidate at all.
 *
 * @author owlzhangfq@gmail.com
 */
class AppSnapshotRetentionServiceTest {

    private static final String APP_ID = "app_1";
    private static final String OTHER_APP_ID = "app_2";
    private static final String KB_ID = "kb_1";

    private AppVersionMapper appVersionMapper;
    private IndexSnapshotService indexSnapshotService;
    private KbProperties properties;
    private AppSnapshotRetentionService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(AppVersion.class);
        appVersionMapper = mock(AppVersionMapper.class);
        indexSnapshotService = mock(IndexSnapshotService.class);
        properties = new KbProperties();
        service = new AppSnapshotRetentionService(appVersionMapper, indexSnapshotService, properties);
    }

    @Test
    void shouldKeepTheThreeMostRecentlyReleasedRetiredSnapshots() {
        when(appVersionMapper.selectList(any())).thenReturn(retired(5));

        assertEquals(2, service.retireExpired());

        // Five retired releases, a window of three: the two least recently served ones lose their snapshot.
        verify(indexSnapshotService).drop(List.of(new AppIndexSnapshot(KB_ID, "es", "kb_1_none_s1")));
        verify(indexSnapshotService).drop(List.of(new AppIndexSnapshot(KB_ID, "es", "kb_1_none_s2")));
        verify(indexSnapshotService, never())
                .drop(List.of(new AppIndexSnapshot(KB_ID, "es", "kb_1_none_s3")));
    }

    @Test
    void shouldHonourAConfiguredWindow() {
        properties.getApp().setSnapshotRetainCount(1);
        when(appVersionMapper.selectList(any())).thenReturn(retired(3));

        assertEquals(2, service.retireExpired());
    }

    @Test
    void shouldClampAWindowOfZeroToOne() {
        // A knob typo must not leave an application without a rollback target, and it must not stop the boot.
        properties.getApp().setSnapshotRetainCount(0);
        when(appVersionMapper.selectList(any())).thenReturn(retired(3));

        assertEquals(2, service.retireExpired());
    }

    @Test
    void shouldClearBothFrozenColumnsSoThePinIsReleased() {
        when(appVersionMapper.selectList(any())).thenReturn(retired(4));

        service.retireExpired();

        // The indices go first and the columns last: clearing the pin while a live snapshot still points at the
        // chunks would let the document version retention archive them.
        verify(indexSnapshotService).drop(List.of(new AppIndexSnapshot(KB_ID, "es", "kb_1_none_s1")));
        verify(appVersionMapper).update(eq(null), any());
    }

    @Test
    void shouldKeepEveryRetiredSnapshotWhileTheWindowIsNotExceeded() {
        when(appVersionMapper.selectList(any())).thenReturn(retired(3));

        assertEquals(0, service.retireExpired());

        verify(indexSnapshotService, never()).drop(anyList());
    }

    @Test
    void shouldApplyTheWindowPerApplicationRatherThanGlobally() {
        List<AppVersion> versions = new ArrayList<>(retired(3));
        List<AppVersion> otherApp = retired(2);
        otherApp.forEach(version -> version.setAppId(OTHER_APP_ID));
        versions.addAll(otherApp);
        when(appVersionMapper.selectList(any())).thenReturn(versions);

        // Six retired releases in total but at most three per application, so nothing expires.
        assertEquals(0, service.retireExpired());
        verify(indexSnapshotService, never()).drop(anyList());
    }

    @Test
    void shouldSkipTheScheduledPassWhenItIsDisabled() {
        properties.getApp().setSnapshotCleanupEnabled(false);

        service.scheduledPass();

        verify(appVersionMapper, never()).selectList(any());
    }

    @Test
    void shouldSwallowAFailureOfTheScheduledPass() {
        when(appVersionMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        // A retention pass that throws would only be retried on the next cron tick either way, and letting the
        // exception escape a scheduled method silently cancels the schedule in some containers.
        service.scheduledPass();

        verify(indexSnapshotService, never()).drop(anyList());
    }

    @Test
    void shouldNeverConsiderTheReleasedVersionACandidate() {
        when(appVersionMapper.selectList(any())).thenReturn(retired(4));

        service.retireExpired();

        // The selection itself is the guarantee: only retired versions that actually froze something are ever
        // loaded, so the version currently serving production traffic out of its snapshot cannot be reached by
        // the window at all - requirement section 4.7 "the released snapshot is never cleaned up".
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppVersion>> wrapper =
                ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(appVersionMapper).selectList(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("status ="));
        assertTrue(wrapper.getValue().getSqlSegment().contains("index_snapshots IS NOT NULL"));
        assertEquals(AppVersionStatus.SUPERSEDED.name(),
                String.valueOf(wrapper.getValue().getParamNameValuePairs().values().iterator().next()));
    }

    /**
     * Retired versions of one application, oldest release first.
     *
     * @param count number of versions
     * @return versions
     */
    private List<AppVersion> retired(int count) {
        List<AppVersion> versions = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            AppVersion version = new AppVersion();
            version.setId((long) i);
            version.setAppVersionId("av_" + i);
            version.setAppId(APP_ID);
            version.setStatus(AppVersionStatus.SUPERSEDED);
            version.setReleasedAt(LocalDateTime.of(2026, 1, i, 0, 0));
            version.setIndexSnapshots(JsonUtil.toJson(
                    List.of(new AppIndexSnapshot(KB_ID, "es", "kb_1_none_s" + i))));
            version.setVisibleVersionIds(JsonUtil.toJson(Map.of(KB_ID, List.of("dv_" + i))));
            versions.add(version);
        }
        return versions;
    }
}
