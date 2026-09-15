package io.kbrag.domain.entity;

import io.kbrag.domain.enums.ExtSourceSyncStatus;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 成功、内容更新与尝试是不同事实，不能混用时间。 */
class SourceHealthTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 10, 0);

    @Test
    void shouldKeepUnknownHistoryAndAdvanceOnlySuccessfulWebFetches() {
        WebSource source = new WebSource();
        source.setLastFetchAt(NOW);
        source.recordOutcome(WebSourceFetchStatus.FAILED, "offline", NOW);
        assertNull(source.getLastSuccessAt());
        assertNull(source.getLastContentChangeAt());
        source.recordOutcome(WebSourceFetchStatus.UNCHANGED, null, NOW.plusHours(1));
        source.recordOutcome(WebSourceFetchStatus.SKIPPED, "trash", NOW.plusHours(2));
        assertEquals(NOW.plusHours(1), source.getLastSuccessAt());
        assertNull(source.getLastContentChangeAt());
    }

    @Test
    void shouldRetainContentProgressEvenWhenExternalScanIsPartial() {
        ExtSource source = new ExtSource();
        source.recordOutcome(ExtSourceSyncStatus.SUCCESS, null, NOW);
        source.contentChanged(NOW.plusHours(1));
        source.recordOutcome(ExtSourceSyncStatus.PARTIAL, "one failed", NOW.plusHours(1));
        assertEquals(NOW, source.getLastSuccessAt());
        assertEquals(NOW.plusHours(1), source.getLastContentChangeAt());
        source.recordOutcome(ExtSourceSyncStatus.FAILED, "offline", NOW.plusHours(2));
        assertEquals(NOW, source.getLastSuccessAt());
    }

    @Test
    void shouldNeverMoveSourceHealthBackwards() {
        WebSource web = new WebSource();
        ExtSource external = new ExtSource();
        for (LocalDateTime time : new LocalDateTime[]{NOW.plusHours(1), NOW}) {
            web.recordOutcome(WebSourceFetchStatus.SUCCESS, null, time);
            external.recordOutcome(ExtSourceSyncStatus.SUCCESS, null, time);
            web.contentChanged(time);
            external.contentChanged(time);
        }
        assertEquals(NOW.plusHours(1), web.getLastSuccessAt());
        assertEquals(NOW.plusHours(1), web.getLastContentChangeAt());
        assertEquals(NOW.plusHours(1), external.getLastSuccessAt());
        assertEquals(NOW.plusHours(1), external.getLastContentChangeAt());
    }
}
