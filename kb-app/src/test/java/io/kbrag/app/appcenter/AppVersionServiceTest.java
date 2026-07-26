package io.kbrag.app.appcenter;

import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the version state machine and the configuration freeze of requirement section 4.7: the snapshot is
 * completed when a draft leaves the draft state, a promotion retires the previous released version in the same
 * transaction, and the call resolution tells a missing version apart from an unpublished one.
 *
 * @author owlzhangfq@gmail.com
 */
class AppVersionServiceTest {

    private static final String APP_ID = "app_1";
    private static final String KB_ID = "kb_1";
    private static final String VERSION_ID = "av_1";

    private AppVersionMapper appVersionMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private EvalDatasetService evalDatasetService;
    private BizIdGenerator bizIdGenerator;
    private KbProperties properties;
    private AppVersionService service;

    @BeforeEach
    void setUp() {
        appVersionMapper = mock(AppVersionMapper.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        evalDatasetService = mock(EvalDatasetService.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        properties = new KbProperties();
        when(bizIdGenerator.appVersionId()).thenReturn(VERSION_ID);
        service = new AppVersionService(appVersionMapper, knowledgeBaseService, evalDatasetService,
                bizIdGenerator, properties);
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
        assertEquals(KB_ID, inherited.getKbId());
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
        assertTrue(e.getMessage().contains("知识库不一致"));
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
        snapshot.setKbId(kbId);
        KbRetrievalConfig retrieval = new KbRetrievalConfig();
        retrieval.setTopN(5);
        retrieval.setRecallTopK(50);
        retrieval.setFusionMode("rrf");
        snapshot.setRetrieval(retrieval);
        return snapshot;
    }
}
