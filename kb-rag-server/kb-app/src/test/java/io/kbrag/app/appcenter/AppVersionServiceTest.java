package io.kbrag.app.appcenter;

import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphFusionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the version state machine and the configuration freeze of requirement section 4.7: the snapshot is
 * completed when a draft leaves the draft state, a promotion retires the previous released version in the same
 * transaction, and the call resolution tells a missing version apart from an unpublished one.
 *
 * <p>It also pins the tenant resolution of the version domain. {@code t_kb_app_version} is a subordinate
 * table with no {@code tenant_id} of its own, so every entry addressing a version by {@code appVersionId} is
 * isolated only while it resolves the owning application through the fenced root first. That is pinned here
 * with the real {@link AppVersionGuard} over a mocked {@link AppService}: an application of another tenant is
 * a null row, precisely what the fence produces on a console thread.
 *
 * @author owlzhangfq@gmail.com
 */
class AppVersionServiceTest {

    private static final String APP_ID = "app_1";
    private static final String KB_ID = "kb_1";
    private static final String KB_ID_2 = "kb_2";
    private static final String VERSION_ID = "av_1";
    /** An application of another tenant: the fence reads it as missing, so {@code find} answers null. */
    private static final String FOREIGN_APP_ID = "app_theirs";
    private static final String DATASET_ID = "evds_1";

    private AppVersionMapper appVersionMapper;
    private AppService appService;
    private KnowledgeBaseService knowledgeBaseService;
    private EvalDatasetService evalDatasetService;
    private KbResourceGuard kbResourceGuard;
    private BizIdGenerator bizIdGenerator;
    private KbProperties properties;
    private AppVersionService service;

    @BeforeEach
    void setUp() {
        appVersionMapper = mock(AppVersionMapper.class);
        appService = mock(AppService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        evalDatasetService = mock(EvalDatasetService.class);
        kbResourceGuard = mock(KbResourceGuard.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        properties = new KbProperties();
        when(bizIdGenerator.appVersionId()).thenReturn(VERSION_ID);
        // The real guard over the mocked application service, so an application the tenant fence filters
        // away is simply a null row here - which is exactly what the fence does on a console thread.
        service = new AppVersionService(new AppVersionGuard(appVersionMapper, appService),
                appVersionMapper, knowledgeBaseService, evalDatasetService, kbResourceGuard,
                bizIdGenerator, new GraphFusionPolicy(), properties);
        // Every version in this class belongs to the one application of the caller's own tenant; the tenant
        // tests point their version at a foreign application instead.
        when(appService.find(APP_ID)).thenReturn(new App());
    }

    @Test
    void shouldNumberTheFirstVersionV1AndTheNextOneV2() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        AppConfigSnapshot snapshot = snapshotOf(KB_ID);

        AppVersion first = service.createDraft(APP_ID, snapshot, null, "初版");
        assertEquals("V1.0", first.getVersion());
        assertEquals(AppVersionStatus.DRAFT, first.getStatus());

        when(appVersionMapper.selectList(any())).thenReturn(List.of(versionOf("V1.0", AppVersionStatus.RELEASED)));
        AppVersion second = service.createDraft(APP_ID, snapshot, null, "第二版");
        assertEquals("V2.0", second.getVersion());
    }

    @Test
    void shouldInheritTheNewestConfigurationWhenThePayloadCarriesNone() {
        AppVersion newest = versionOf("V1.0", AppVersionStatus.RELEASED);
        AppConfigSnapshot stored = snapshotOf(KB_ID);
        stored.getRetrieval().setTopN(11);
        newest.setConfig(JsonUtil.toJson(stored));
        newest.setGateDatasetId("evds_1");
        when(appVersionMapper.selectList(any())).thenReturn(List.of(newest));

        service.createDraft(APP_ID, null, null, null);

        ArgumentCaptor<AppVersion> captor = ArgumentCaptor.forClass(AppVersion.class);
        verify(appVersionMapper).insert(captor.capture());
        AppConfigSnapshot inherited = JsonUtil.parse(captor.getValue().getConfig(), AppConfigSnapshot.class);
        assertEquals(11, inherited.getRetrieval().getTopN());
        assertEquals(List.of(KB_ID), inherited.kbIds());
        // The gate binding is inherited too, so iterating on a gated application keeps its gate.
        assertEquals("evds_1", captor.getValue().getGateDatasetId());
    }

    @Test
    void shouldCompleteTheSnapshotWhenADraftLeavesTheDraftState() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        AppConfigSnapshot sparse = new AppConfigSnapshot();
        sparse.setKbId(KB_ID);
        sparse.setRetrieval(new KbRetrievalConfig());
        draft.setConfig(JsonUtil.toJson(sparse));
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        when(knowledgeBaseService.require(KB_ID)).thenReturn(new KnowledgeBase());
        properties.getRetrieval().setDefaultTopN(6);
        properties.getRetrieval().setDefaultRecallTopK(40);

        AppVersion submitted = service.submitTest(VERSION_ID);

        assertEquals(AppVersionStatus.TESTING, submitted.getStatus());
        AppConfigSnapshot frozen = JsonUtil.parse(submitted.getConfig(), AppConfigSnapshot.class);
        // Every unset field is materialised now, so a later change of a deployment default cannot alter the
        // behaviour of a version the gate already measured.
        assertEquals(6, frozen.getRetrieval().getTopN());
        assertEquals(40, frozen.getRetrieval().getRecallTopK());
        assertEquals("rrf", frozen.getRetrieval().getFusionMode());
        // The threshold stays null on purpose: null means "no absolute filtering", not "unset".
        assertNull(frozen.getRetrieval().getScoreThreshold());
    }

    @Test
    void shouldRefuseToSubmitAVersionWithoutAKnowledgeBase() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        draft.setConfig(JsonUtil.toJson(new AppConfigSnapshot()));
        when(appVersionMapper.selectOne(any())).thenReturn(draft);

        BizException e = assertThrows(BizException.class, () -> service.submitTest(VERSION_ID));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("知识库"));
    }

    @Test
    void shouldRefuseAGateDataSetMeasuringAnotherKnowledgeBase() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        draft.setConfig(JsonUtil.toJson(snapshotOf(KB_ID)));
        draft.setGateDatasetId("evds_other");
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        when(knowledgeBaseService.require(KB_ID)).thenReturn(new KnowledgeBase());
        EvalDataset dataset = new EvalDataset();
        dataset.setKbId("kb_other");
        when(evalDatasetService.require("evds_other")).thenReturn(dataset);

        BizException e = assertThrows(BizException.class, () -> service.submitTest(VERSION_ID));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("不在应用关联的知识库范围内"));
    }

    @Test
    void shouldRefuseToBindAGateDataSetAfterAGateAlreadyRan() {
        AppVersion gated = versionOf("V1.0", AppVersionStatus.GATE_BLOCKED);
        when(appVersionMapper.selectOne(any())).thenReturn(gated);

        BizException e = assertThrows(BizException.class,
                () -> service.setGateDataset(VERSION_ID, "evds_1"));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
    }

    @Test
    void shouldClearTheGateBindingOnABlankDataSetId() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        draft.setGateDatasetId("evds_1");
        draft.setConfig(JsonUtil.toJson(snapshotOf(KB_ID)));
        when(appVersionMapper.selectOne(any())).thenReturn(draft);

        AppVersion updated = service.setGateDataset(VERSION_ID, "");

        assertNull(updated.getGateDatasetId());
    }

    @Test
    void shouldRetireThePreviousReleasedVersionBeforePromotingTheCandidate() {
        AppVersion candidate = versionOf("V2.0", AppVersionStatus.GATE_PASSED);
        candidate.setAppVersionId(VERSION_ID);
        AppVersion previous = versionOf("V1.0", AppVersionStatus.RELEASED);
        previous.setAppVersionId("av_previous");
        // require() and currentReleased() both go through selectOne, in that order.
        when(appVersionMapper.selectOne(any())).thenReturn(candidate, previous);

        AppVersion promoted = service.promote(VERSION_ID, false, "admin");

        assertEquals(AppVersionStatus.RELEASED, promoted.getStatus());
        assertTrue(promoted.getReleasedAt() != null);
        assertEquals(0, promoted.getForceReleased());
        ArgumentCaptor<AppVersion> captor = ArgumentCaptor.forClass(AppVersion.class);
        verify(appVersionMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        // The retired row is written first: the schema allows a single released version per application, so
        // promoting first would collide with the row that is about to be retired.
        assertEquals(AppVersionStatus.SUPERSEDED, captor.getAllValues().get(0).getStatus());
        assertEquals(AppVersionStatus.RELEASED, captor.getAllValues().get(1).getStatus());
    }

    @Test
    void shouldRecordWhoForcedARelease() {
        AppVersion candidate = versionOf("V1.0", AppVersionStatus.GATE_BLOCKED);
        when(appVersionMapper.selectOne(any())).thenReturn(candidate, null);

        AppVersion promoted = service.promote(VERSION_ID, true, "admin");

        assertEquals(1, promoted.getForceReleased());
        assertEquals("admin", promoted.getForceOperator());
        assertTrue(promoted.forced());
    }

    @Test
    void shouldRefuseToPromoteTheAlreadyReleasedVersion() {
        AppVersion released = versionOf("V1.0", AppVersionStatus.RELEASED);
        released.setAppVersionId(VERSION_ID);
        when(appVersionMapper.selectOne(any())).thenReturn(released, released);

        BizException e = assertThrows(BizException.class, () -> service.promote(VERSION_ID, false, "admin"));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
    }

    @Test
    void shouldRollBackOnlyFromTheRetiredState() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        when(appVersionMapper.selectOne(any())).thenReturn(draft);

        assertThrows(BizException.class, () -> service.rollback(VERSION_ID));
    }

    @Test
    void shouldRollBackARetiredVersionToReleased() {
        AppVersion retired = versionOf("V1.0", AppVersionStatus.SUPERSEDED);
        retired.setAppVersionId(VERSION_ID);
        when(appVersionMapper.selectOne(any())).thenReturn(retired, retired, null);

        AppVersion restored = service.rollback(VERSION_ID);

        assertEquals(AppVersionStatus.RELEASED, restored.getStatus());
    }

    @Test
    void shouldRouteACallWithoutAVersionToTheReleasedOne() {
        AppVersion released = versionOf("V3.0", AppVersionStatus.RELEASED);
        when(appVersionMapper.selectOne(any())).thenReturn(released);

        assertEquals("V3.0", service.resolveForCall(APP_ID, null).getVersion());
    }

    @Test
    void shouldReportVersionNotPublishedWhenNothingWasReleasedYet() {
        when(appVersionMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.resolveForCall(APP_ID, null));

        assertEquals(ErrorCode.VERSION_NOT_PUBLISHED, e.getErrorCode());
        assertEquals(409, e.getErrorCode().getHttpStatus());
    }

    @Test
    void shouldAllowAnExplicitTestVersionForACanary() {
        AppVersion testing = versionOf("V2.0", AppVersionStatus.TESTING);
        when(appVersionMapper.selectOne(any())).thenReturn(testing);

        assertEquals(AppVersionStatus.TESTING, service.resolveForCall(APP_ID, "V2.0").getStatus());
    }

    @Test
    void shouldReportVersionNotFoundForAnUnknownVersion() {
        when(appVersionMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.resolveForCall(APP_ID, "V9.0"));

        assertEquals(ErrorCode.VERSION_NOT_FOUND, e.getErrorCode());
        assertEquals(404, e.getErrorCode().getHttpStatus());
    }

    @Test
    void shouldReportVersionNotFoundForARetiredVersionRatherThanRevealingItsState() {
        when(appVersionMapper.selectOne(any())).thenReturn(versionOf("V1.0", AppVersionStatus.SUPERSEDED));

        BizException e = assertThrows(BizException.class, () -> service.resolveForCall(APP_ID, "V1.0"));

        assertEquals(ErrorCode.VERSION_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void shouldTellAnUnpublishedVersionApartFromAMissingOne() {
        when(appVersionMapper.selectOne(any())).thenReturn(versionOf("V2.0", AppVersionStatus.GATE_BLOCKED));

        BizException e = assertThrows(BizException.class, () -> service.resolveForCall(APP_ID, "V2.0"));

        assertEquals(ErrorCode.VERSION_NOT_PUBLISHED, e.getErrorCode());
    }

    @Test
    void shouldFreezeEveryLinkedKnowledgeBaseWithItsWeight() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        AppConfigSnapshot stored = new AppConfigSnapshot();
        stored.setKbRefs(List.of(new KbRef(KB_ID, 3), new KbRef(KB_ID_2, 1)));
        stored.setRetrieval(new KbRetrievalConfig());
        draft.setConfig(JsonUtil.toJson(stored));
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        when(knowledgeBaseService.require(any())).thenReturn(new KnowledgeBase());

        service.submitTest(VERSION_ID);

        AppConfigSnapshot frozen = JsonUtil.parse(draft.getConfig(), AppConfigSnapshot.class);
        assertEquals(List.of(KB_ID, KB_ID_2), frozen.kbIds());
        assertEquals(3, frozen.getKbRefs().get(0).effectiveWeight());
        assertEquals(1, frozen.getKbRefs().get(1).effectiveWeight());
        // A snapshot that never configured routing freezes it as off rather than as absent, so a later change
        // of the built in default cannot alter what the gate measured.
        assertFalse(frozen.routingOrDefaults().isEnabled());
    }

    @Test
    void shouldRewriteALegacySingleBaseSnapshotIntoTheMultiBaseShapeOnFreeze() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        draft.setConfig("{\"kb_id\":\"" + KB_ID + "\",\"retrieval\":{}}");
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        when(knowledgeBaseService.require(any())).thenReturn(new KnowledgeBase());

        service.submitTest(VERSION_ID);

        assertEquals(List.of(KB_ID),
                JsonUtil.parse(draft.getConfig(), AppConfigSnapshot.class).kbIds());
    }

    @Test
    void shouldRefuseAVersionWithoutAnyKnowledgeBase() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setRetrieval(new KbRetrievalConfig());

        BizException e = assertThrows(BizException.class,
                () -> service.createDraft(APP_ID, snapshot, null, "无库"));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("至少一个知识库"));
    }

    @Test
    void shouldRefuseMoreKnowledgeBasesThanTheConfiguredMaximum() {
        properties.getRetrieval().setMaxLinkedKb(2);
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(new KbRef("kb_a", 1), new KbRef("kb_b", 1), new KbRef("kb_c", 1)));

        BizException e = assertThrows(BizException.class,
                () -> service.createDraft(APP_ID, snapshot, null, "超上限"));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("不能超过 2 个"));
    }

    @Test
    void shouldRefuseTheSameKnowledgeBaseLinkedTwice() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(new KbRef(KB_ID, 1), new KbRef(KB_ID, 2)));

        BizException e = assertThrows(BizException.class,
                () -> service.createDraft(APP_ID, snapshot, null, "重复库"));

        // Two entries for one base would give it two quotas out of one budget, so the allocation would no
        // longer sum to the cap the rerank protection is built on.
        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("重复关联"));
    }

    @Test
    void shouldRefuseANonPositiveQuotaWeight() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(new KbRef(KB_ID, 0), new KbRef(KB_ID_2, 1)));

        BizException e = assertThrows(BizException.class,
                () -> service.createDraft(APP_ID, snapshot, null, "零权重"));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("正整数"));
    }

    @Test
    void shouldRefuseAKnowledgeBaseThatDoesNotExist() {
        when(appVersionMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseService.require("kb_missing"))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "knowledge base not found"));
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(new KbRef(KB_ID, 1), new KbRef("kb_missing", 1)));

        BizException e = assertThrows(BizException.class,
                () -> service.createDraft(APP_ID, snapshot, null, "库不存在"));

        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    void shouldAcceptAGateDataSetMeasuringAnyOfTheLinkedKnowledgeBases() {
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        AppConfigSnapshot stored = new AppConfigSnapshot();
        stored.setKbRefs(List.of(new KbRef(KB_ID, 1), new KbRef(KB_ID_2, 1)));
        draft.setConfig(JsonUtil.toJson(stored));
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        EvalDataset dataset = new EvalDataset();
        dataset.setKbId(KB_ID_2);
        when(evalDatasetService.require("evds_second")).thenReturn(dataset);

        // The dual run stays single base evaluation; requiring the data set to cover every linked base would
        // make the gate impossible to satisfy for a multi base application.
        assertEquals("evds_second", service.setGateDataset(VERSION_ID, "evds_second").getGateDatasetId());
    }

    @Test
    void shouldRefuseTheVersionAddressedEntriesOfAnotherTenant() {
        // Every console entry of this domain names an app_version_id and nothing else, so without the
        // resolution of the owning application the fence never sees a statement it may trim. A tenant
        // holding app:release could otherwise release or roll back another tenant's application, and
        // app:read alone returned its configuration snapshot - linked bases and model parameters included.
        when(appVersionMapper.selectOne(any())).thenReturn(foreignVersion(AppVersionStatus.SUPERSEDED));
        when(appService.find(FOREIGN_APP_ID)).thenReturn(null);

        assertNotFound(() -> service.require(VERSION_ID));
        assertNotFound(() -> service.submitTest(VERSION_ID));
        assertNotFound(() -> service.setGateDataset(VERSION_ID, DATASET_ID));
        assertNotFound(() -> service.promote(VERSION_ID, true, "admin"));
        assertNotFound(() -> service.rollback(VERSION_ID));

        // Past the one lookup that locates the row, nothing happened: no state was written and no data set
        // was read, so a rejected caller cannot even learn whether the version had a gate binding.
        verify(appVersionMapper, never()).updateById(any(AppVersion.class));
        verify(evalDatasetService, never()).require(anyString());
    }

    @Test
    void shouldReportAForeignVersionExactlyAsAMissingOne() {
        // The two must be indistinguishable. Reporting the second hop as APP_NOT_FOUND would leak the same
        // fact in a different shape: the difference between the codes tells the caller their guess is real.
        when(appVersionMapper.selectOne(any())).thenReturn(null);
        BizException missing = assertThrows(BizException.class, () -> service.require(VERSION_ID));

        when(appVersionMapper.selectOne(any())).thenReturn(foreignVersion(AppVersionStatus.RELEASED));
        when(appService.find(FOREIGN_APP_ID)).thenReturn(null);
        BizException foreign = assertThrows(BizException.class, () -> service.require(VERSION_ID));

        assertEquals(missing.getErrorCode(), foreign.getErrorCode());
        assertEquals(missing.getMessage(), foreign.getMessage());
        // Neither message names the application the version belongs to.
        assertFalse(foreign.getMessage().contains(FOREIGN_APP_ID));
    }

    @Test
    void shouldAnswerTheTenantBeforeTheDataScope() {
        // Two different questions, and only this order keeps them from leaking into each other. Inside the
        // own tenant a data set the caller's roles do not name is a permission problem: 403, actionable.
        // Outside the tenant the version must not exist at all: 404. Asking the data scope first - which is
        // where this check used to sit, in the controller - answers 403 for a foreign version too, and that
        // difference is exactly what tells a caller which ids exist in other tenants.
        AppVersion draft = versionOf("V1.0", AppVersionStatus.DRAFT);
        when(appVersionMapper.selectOne(any())).thenReturn(draft);
        org.mockito.Mockito.doThrow(BizException.forbidden("knowledge base outside your data scope"))
                .when(kbResourceGuard).requireDatasetAccess(DATASET_ID);

        BizException forbidden = assertThrows(BizException.class,
                () -> service.setGateDataset(VERSION_ID, DATASET_ID));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCode());

        when(appVersionMapper.selectOne(any())).thenReturn(foreignVersion(AppVersionStatus.DRAFT));
        when(appService.find(FOREIGN_APP_ID)).thenReturn(null);
        assertNotFound(() -> service.setGateDataset(VERSION_ID, DATASET_ID));

        // The foreign call never reached the data scope check at all: one invocation, from the first half.
        verify(kbResourceGuard, times(1)).requireDatasetAccess(DATASET_ID);
        verify(appVersionMapper, never()).updateById(any(AppVersion.class));
    }

    private void assertNotFound(Executable call) {
        BizException e = assertThrows(BizException.class, call);
        assertEquals(ErrorCode.VERSION_NOT_FOUND, e.getErrorCode());
        assertEquals(404, e.getErrorCode().getHttpStatus());
    }

    /** A version whose application the fence filters away, the console shape of a cross tenant call. */
    private AppVersion foreignVersion(AppVersionStatus status) {
        AppVersion row = versionOf("V1.0", status);
        row.setAppId(FOREIGN_APP_ID);
        return row;
    }

    private AppVersion versionOf(String version, AppVersionStatus status) {
        AppVersion row = new AppVersion();
        row.setAppVersionId(VERSION_ID);
        row.setAppId(APP_ID);
        row.setVersion(version);
        row.setStatus(status);
        row.setConfig(JsonUtil.toJson(snapshotOf(KB_ID)));
        row.setForceReleased(0);
        return row;
    }

    private AppConfigSnapshot snapshotOf(String kbId) {
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(KbRef.of(kbId)));
        KbRetrievalConfig retrieval = new KbRetrievalConfig();
        retrieval.setTopN(5);
        retrieval.setRecallTopK(50);
        retrieval.setFusionMode("rrf");
        snapshot.setRetrieval(retrieval);
        return snapshot;
    }
}
