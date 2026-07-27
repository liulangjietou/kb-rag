package io.kbrag.app.appcenter;

import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.IndexSnapshotService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.KbRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the all-or-nothing freeze of requirement section 4.7: both columns are frozen together per linked
 * knowledge base, and a base whose snapshot fails aborts the release after deleting the indices already created.
 *
 * @author owlzhangfq@gmail.com
 */
class AppReleaseSnapshotServiceTest {

    private static final String APP_VERSION_ID = "av_1";
    private static final String KB_A = "kb_a";
    private static final String KB_B = "kb_b";

    private IndexSnapshotService indexSnapshotService;
    private ActiveVersionResolver activeVersionResolver;
    private AppReleaseSnapshotService service;

    @BeforeEach
    void setUp() {
        indexSnapshotService = mock(IndexSnapshotService.class);
        activeVersionResolver = mock(ActiveVersionResolver.class);
        service = new AppReleaseSnapshotService(indexSnapshotService, activeVersionResolver);
    }

    @Test
    void shouldFreezeAnIndexAndAVisibilitySetForEveryLinkedBase() {
        when(indexSnapshotService.snapshot(KB_A))
                .thenReturn(List.of(new AppIndexSnapshot(KB_A, "es", "kb_a_none_s1")));
        when(indexSnapshotService.snapshot(KB_B))
                .thenReturn(List.of(new AppIndexSnapshot(KB_B, "es", "kb_b_none_s1")));
        when(activeVersionResolver.activeVersionIds(KB_A)).thenReturn(List.of("dv_a1", "dv_a2"));
        when(activeVersionResolver.activeVersionIds(KB_B)).thenReturn(List.of("dv_b1"));

        AppReleaseSnapshotService.ReleaseSnapshot frozen = service.freeze(version(), snapshot(KB_A, KB_B));

        assertEquals(2, frozen.indexSnapshots().size());
        // The index and the set are two halves of one fact: the snapshot holds chunks of many versions and the
        // set says which of them the release may see.
        assertEquals(List.of("dv_a1", "dv_a2"), frozen.visibleVersionIds().get(KB_A));
        assertEquals(List.of("dv_b1"), frozen.visibleVersionIds().get(KB_B));
    }

    @Test
    void shouldAbortAndRollBackEveryIndexCreatedWhenOneBaseFails() {
        AppIndexSnapshot created = new AppIndexSnapshot(KB_A, "es", "kb_a_none_s1");
        when(indexSnapshotService.snapshot(KB_A)).thenReturn(List.of(created));
        when(activeVersionResolver.activeVersionIds(KB_A)).thenReturn(List.of("dv_a1"));
        when(indexSnapshotService.snapshot(KB_B)).thenThrow(new IllegalStateException("engine down"));

        assertThrows(BizException.class, () -> service.freeze(version(), snapshot(KB_A, KB_B)));

        // A half snapshotted release would serve one base frozen and the other live, which is neither the old
        // behaviour nor the new one, so what was created is deleted again.
        verify(indexSnapshotService).drop(List.of(created));
    }

    @Test
    void shouldRefuseToFreezeAVersionWithoutAnyLinkedBase() {
        assertThrows(BizException.class, () -> service.freeze(version(), new AppConfigSnapshot()));

        verify(indexSnapshotService, never()).snapshot(KB_A);
    }

    @Test
    void shouldDropTheIndicesOfADiscardedSnapshot() {
        AppIndexSnapshot created = new AppIndexSnapshot(KB_A, "es", "kb_a_none_s1");

        service.discard(new AppReleaseSnapshotService.ReleaseSnapshot(List.of(created), java.util.Map.of()));

        verify(indexSnapshotService).drop(List.of(created));
    }

    @Test
    void shouldIgnoreDiscardingNothing() {
        service.discard(null);

        verify(indexSnapshotService, never()).drop(anyList());
    }

    private AppVersion version() {
        AppVersion version = new AppVersion();
        version.setAppVersionId(APP_VERSION_ID);
        version.setAppId("app_1");
        return version;
    }

    private AppConfigSnapshot snapshot(String... kbIds) {
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(java.util.Arrays.stream(kbIds).map(KbRef::of).toList());
        return snapshot;
    }
}
