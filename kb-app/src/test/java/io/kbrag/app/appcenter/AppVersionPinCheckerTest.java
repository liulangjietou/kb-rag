package io.kbrag.app.appcenter;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the archiving protection of requirement sections 4.1 and 4.7: a document version referenced by any
 * undeleted application version is pinned, a retired version pins just as much as the released one, and the pin
 * disappears when the retention pass clears the frozen column.
 *
 * @author owlzhangfq@gmail.com
 */
class AppVersionPinCheckerTest {

    private static final String DOC_ID = "doc_1";
    private static final String KB_ID = "kb_1";
    private static final String PINNED_VERSION = "dv_1";
    private static final String FREE_VERSION = "dv_2";
    private static final String RELEASED_APP_VERSION = "av_released";
    private static final String SUPERSEDED_APP_VERSION = "av_superseded";

    private AppVersionMapper appVersionMapper;
    private DocumentVersionMapper documentVersionMapper;
    private AppVersionPinChecker checker;

    @BeforeEach
    void setUp() {
        appVersionMapper = mock(AppVersionMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        when(documentVersionMapper.selectList(any()))
                .thenReturn(List.of(documentVersion(PINNED_VERSION), documentVersion(FREE_VERSION)));
        checker = new AppVersionPinChecker(appVersionMapper, documentVersionMapper);
    }

    @Test
    void shouldPinAVersionAnyApplicationVersionReferences() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of(
                appVersion(RELEASED_APP_VERSION, AppVersionStatus.RELEASED, List.of(PINNED_VERSION))));

        assertEquals(Map.of(PINNED_VERSION, List.of(RELEASED_APP_VERSION)), checker.pinnedBy(DOC_ID));
        assertTrue(checker.pinnedVersionIds(DOC_ID).contains(PINNED_VERSION));
        // A version nobody froze stays archivable, which is what keeps the retention window working at all.
        assertFalse(checker.pinnedVersionIds(DOC_ID).contains(FREE_VERSION));
    }

    @Test
    void shouldPinFromARetiredApplicationVersionWhoseSnapshotIsStillThere() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of(
                appVersion(SUPERSEDED_APP_VERSION, AppVersionStatus.SUPERSEDED, List.of(PINNED_VERSION))));

        // A retired version owns its own index copy, but the chunk text lives only in MySQL: archiving would
        // leave its snapshot recalling ids whose rows are gone, so a rollback would answer emptily.
        assertEquals(List.of(SUPERSEDED_APP_VERSION), checker.pinnedBy(DOC_ID).get(PINNED_VERSION));
    }

    @Test
    void shouldReleaseThePinOnceTheRetentionPassClearedTheColumn() {
        AppVersion retired = appVersion(SUPERSEDED_APP_VERSION, AppVersionStatus.SUPERSEDED,
                List.of(PINNED_VERSION));
        retired.setVisibleVersionIds(null);
        retired.setIndexSnapshots(null);
        when(appVersionMapper.selectList(any())).thenReturn(List.of(retired));

        // The pin and the data it protects disappear in the same statement, so no window exists in which one
        // outlives the other.
        assertTrue(checker.pinnedBy(DOC_ID).isEmpty());
        assertTrue(checker.pinnedVersionIds(DOC_ID).isEmpty());
    }

    @Test
    void shouldListEveryApplicationVersionHoldingTheSameDocumentVersion() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of(
                appVersion(RELEASED_APP_VERSION, AppVersionStatus.RELEASED, List.of(PINNED_VERSION)),
                appVersion(SUPERSEDED_APP_VERSION, AppVersionStatus.SUPERSEDED, List.of(PINNED_VERSION))));

        // The console explains a disabled archive action with the list, so both references have to be reported.
        assertEquals(List.of(RELEASED_APP_VERSION, SUPERSEDED_APP_VERSION),
                checker.pinnedBy(DOC_ID).get(PINNED_VERSION));
    }

    @Test
    void shouldIgnoreFrozenVersionsOfOtherDocuments() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of(
                appVersion(RELEASED_APP_VERSION, AppVersionStatus.RELEASED, List.of("dv_other"))));

        assertTrue(checker.pinnedBy(DOC_ID).isEmpty());
    }

    @Test
    void shouldReportNothingForADocumentThatHasNoVersion() {
        when(documentVersionMapper.selectList(any())).thenReturn(List.of());

        assertTrue(checker.pinnedBy(DOC_ID).isEmpty());
    }

    private DocumentVersion documentVersion(String versionId) {
        DocumentVersion version = new DocumentVersion();
        version.setDocId(DOC_ID);
        version.setVersionId(versionId);
        return version;
    }

    private AppVersion appVersion(String appVersionId, AppVersionStatus status, List<String> frozen) {
        AppVersion version = new AppVersion();
        version.setAppVersionId(appVersionId);
        version.setAppId("app_1");
        version.setStatus(status);
        version.setVisibleVersionIds(JsonUtil.toJson(Map.of(KB_ID, frozen)));
        version.setIndexSnapshots(JsonUtil.toJson(List.of()));
        return version;
    }
}
