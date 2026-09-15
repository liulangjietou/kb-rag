package io.kbrag.app.appcenter;

import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppCorpusDocumentMapper;
import io.kbrag.domain.model.AppCorpusDocument;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 覆盖发布资料边界、当前授权、缺失历史与分页前统计，所有查询均为只读替身。 */
class AppVersionComparisonServiceTest {
    private final AppVersionGuard versions = mock(AppVersionGuard.class);
    private final KbResourceGuard guard = mock(KbResourceGuard.class);
    private final ActiveVersionResolver active = mock(ActiveVersionResolver.class);
    private final DocumentAclService acl = mock(DocumentAclService.class);
    private final AppCorpusDocumentMapper documents = mock(AppCorpusDocumentMapper.class);
    private final AppVersionComparisonService service = new AppVersionComparisonService(versions, guard, active, acl, documents);

    @BeforeEach
    void setUp() {
        bind(true, Set.of(PermissionCodes.APP_READ, PermissionCodes.KB_READ));
        when(acl.trimRestricted(anyString(), anyList())).thenAnswer(call -> call.getArgument(1));
    }

    @AfterEach
    void tearDown() { UserContextHolder.clear(); }

    @Test
    void sameCountsStillDetectAddedRemovedAndReplacedDocumentVersionsBeforePagination() {
        frozen("before", List.of("old", "gone"));
        frozen("after", List.of("new", "added"));
        when(documents.selectVersions("tenant", "kb", List.of("old", "gone")))
                .thenReturn(List.of(doc("old", "a"), doc("gone", "b")));
        when(documents.selectVersions("tenant", "kb", List.of("new", "added")))
                .thenReturn(List.of(doc("new", "a"), doc("added", "c")));
        var result = service.compareCorpus("after", "before", 2, 1);
        assertAll(() -> assertTrue(result.comparable()), () -> assertEquals(3L, result.total()),
                () -> assertEquals(1L, result.added()), () -> assertEquals(1L, result.removed()),
                () -> assertEquals(1L, result.updated()), () -> assertEquals(2, result.baseline().documentCount()),
                () -> assertEquals(2, result.candidate().documentCount()),
                () -> assertEquals("b", result.items().get(0).docId()),
                () -> assertEquals(AppVersionComparisonService.Change.REMOVED, result.items().get(0).change()));
        verifyNoInteractions(active);
    }

    @Test
    void explicitEmptyFrozenCorpusDoesNotFallBackToCurrentDocuments() {
        frozen("version", List.of());
        var result = service.compareCorpus("version", null, 1, 20);
        assertEquals(AppVersionComparisonService.Source.FROZEN, result.candidate().source());
        assertEquals(0L, result.total());
        assertTrue(result.comparable());
        verifyNoInteractions(active, documents);
    }

    @Test
    void retiredSnapshotIsUnavailableInsteadOfAnEmptyOrCurrentBaseline() {
        var retired = frozen("before", List.of());
        retired.setVisibleVersionIds(null);
        retired.setStatus(AppVersionStatus.SUPERSEDED);
        frozen("after", List.of());
        var result = service.compareCorpus("after", "before", 1, 20);
        assertFalse(result.comparable());
        assertNull(result.total());
        assertNull(result.baseline().documentCount());
        assertEquals(AppVersionComparisonService.Source.UNAVAILABLE, result.baseline().source());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(active, documents);
    }

    @Test
    void draftUsesCurrentVersionsAndHiddenDocumentsNeverAffectCountsOrPages() {
        var draft = frozen("draft", List.of());
        draft.setStatus(AppVersionStatus.DRAFT);
        draft.setVisibleVersionIds(null);
        when(active.activeVersionIds("kb")).thenReturn(List.of("public", "secret"));
        when(acl.trimRestricted("kb", List.of("public", "secret"))).thenReturn(List.of("public"));
        when(documents.selectVersions("tenant", "kb", List.of("public"))).thenReturn(List.of(doc("public", "a")));
        var result = service.compareCorpus("draft", null, 1, 20);
        assertEquals(AppVersionComparisonService.Source.CURRENT, result.candidate().source());
        assertEquals(1, result.candidate().documentCount());
        assertEquals(1L, result.total());
        assertFalse(JsonUtil.toJson(result).contains("secret"));
        verify(documents).selectVersions("tenant", "kb", List.of("public"));
    }

    @Test
    void missingDocumentMetadataCannotBeReportedAsNoDifference() {
        frozen("version", List.of("missing"));
        when(documents.selectVersions("tenant", "kb", List.of("missing"))).thenReturn(List.of());
        var result = service.compareCorpus("version", null, 1, 20);
        assertFalse(result.comparable());
        assertFalse(result.candidate().complete());
        assertNull(result.candidate().documentCount());
        assertNull(result.total());
    }

    @Test
    void crossApplicationVersionIsIndistinguishableFromMissingBeforeDocumentReads() {
        frozen("candidate", List.of());
        frozen("foreign", List.of()).setAppId("another-app");
        assertEquals(ErrorCode.VERSION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.compareCorpus("candidate", "foreign", 1, 20)).getErrorCode());
        verifyNoInteractions(guard, active, documents, acl);
    }

    @Test
    void tenantGuardFailureStopsBeforeDataScopeAndMetadataReads() {
        when(versions.require("foreign")).thenThrow(new BizException(ErrorCode.VERSION_NOT_FOUND, "application version not found"));
        assertEquals(ErrorCode.VERSION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.compareCorpus("foreign", null, 1, 20)).getErrorCode());
        verifyNoInteractions(guard, active, documents, acl);
    }

    @Test
    void appAndKnowledgeBasePermissionsDoNotBypassApplicationOrKnowledgeBaseScope() {
        frozen("version", List.of());
        bind(false, Set.of(PermissionCodes.APP_READ, PermissionCodes.KB_READ));
        assertThrows(BizException.class, () -> service.compareCorpus("version", null, 1, 20));
        verifyNoInteractions(guard, active, documents, acl);
        bind(true, Set.of(PermissionCodes.APP_READ));
        assertThrows(BizException.class, () -> service.compareCorpus("version", null, 1, 20));
        bind(true, Set.of(PermissionCodes.APP_READ, PermissionCodes.KB_READ));
        doThrow(BizException.forbidden("outside kb scope")).when(guard).requireKb("kb");
        assertThrows(BizException.class, () -> service.compareCorpus("version", null, 1, 20));
        verifyNoInteractions(active, documents, acl);
    }

    @Test
    void largeSnapshotsUseBoundedReadsAndOutOfRangePagesAreEmpty() {
        frozen("version", IntStream.range(0, 501).mapToObj(i -> "v" + i).toList());
        when(documents.selectVersions(eq("tenant"), eq("kb"), anyList())).thenAnswer(call -> {
            List<String> batch = call.getArgument(2);
            assertTrue(batch.size() <= 500);
            return batch.stream().map(id -> doc(id, id)).toList();
        });
        var result = service.compareCorpus("version", null, Integer.MAX_VALUE, 100);
        assertTrue(result.items().isEmpty());
        assertEquals(501L, result.total());
        verify(documents, times(2)).selectVersions(eq("tenant"), eq("kb"), anyList());
    }

    private AppVersion frozen(String id, List<String> ids) {
        AppVersion version = new AppVersion();
        version.setAppVersionId(id);
        version.setAppId("app");
        version.setConfig("{\"kb_id\":\"kb\"}");
        version.setStatus(AppVersionStatus.RELEASED);
        version.setVisibleVersionIds(JsonUtil.toJson(Map.of("kb", ids)));
        when(versions.require(id)).thenReturn(version);
        return version;
    }

    private AppCorpusDocument doc(String version, String doc) { return new AppCorpusDocument(version, doc, doc + ".md", version); }

    private void bind(boolean appScopeAll, Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "user", "User", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of(), appScopeAll, Set.of()));
    }
}
