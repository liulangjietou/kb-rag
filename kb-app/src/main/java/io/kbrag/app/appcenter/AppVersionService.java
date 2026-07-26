package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppPromptConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Application version lifecycle: the draft, the state machine transitions and the version resolution the
 * open API performs on every call, requirement section 4.7.
 *
 * <p><b>Every transition goes through {@link #transition}.</b> The allowed moves live on
 * {@link AppVersionStatus}, so a caller cannot invent one, and the rejection happens before a row is
 * touched. This is what keeps "at most one released version" and "a retired version is never callable" true
 * regardless of which endpoint was called.
 *
 * <p><b>The release gate is not here.</b> This service knows how to promote a version and how to retire the
 * previous one; deciding whether it may be promoted is {@link ReleaseGateService}'s job. Keeping the two
 * apart is what allows the gate to run asynchronously without the state machine growing a second, parallel
 * definition of what a legal move is.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppVersionService {

    private static final String VERSION_PREFIX = "V";
    private static final String VERSION_MINOR_SUFFIX = ".0";
    private static final int FORCED = 1;
    private static final int NOT_FORCED = 0;

    private final AppVersionMapper appVersionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EvalDatasetService evalDatasetService;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Creates the next version of an application from a complete configuration, requirement section 4.7
     * "create a version from the current draft configuration".
     *
     * <p>The console's configuration form is this call: there is no separate draft entity to edit, a saved
     * form is a new {@code DRAFT} version. That removes the ambiguity of a draft that drifts away from the
     * version it was cloned from, and it makes the version list the complete history of what was configured.
     *
     * <p>An unset field is inherited from the newest existing version, so the common case - change one
     * parameter of what is live - does not require the caller to restate the whole configuration.
     *
     * @param appId         owning application
     * @param snapshot      configuration of the new version, {@code null} inherits the newest version's
     * @param gateDatasetId baseline data set to bind, {@code null} inherits the newest version's
     * @param changelog     version description
     * @return created draft version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion createDraft(String appId, AppConfigSnapshot snapshot, String gateDatasetId,
                                  String changelog) {
        List<AppVersion> versions = listByApp(appId);
        AppVersion newest = CollectionUtils.isEmpty(versions) ? null : versions.get(0);
        AppConfigSnapshot effective = snapshot != null ? snapshot
                : (newest == null ? defaultSnapshot(null) : parseConfig(newest));
        String effectiveDataset = gateDatasetId != null ? gateDatasetId
                : (newest == null ? null : newest.getGateDatasetId());
        AppVersion created = insertDraft(appId, nextVersion(versions), effective, effectiveDataset, changelog);
        log.info("application version created, appId={}, appVersionId={}, version={}",
                appId, created.getAppVersionId(), created.getVersion());
        return created;
    }

    /**
     * Binds or clears the baseline evaluation data set of a version.
     *
     * <p>Editable while the version is a draft or a test version, and not afterwards: once a gate ran, the
     * data set it ran against is part of the evidence behind the verdict, and swapping it would leave a
     * report that cannot be reproduced.
     *
     * @param appVersionId version business id
     * @param datasetId    data set to bind, blank or {@code null} clears the binding
     * @return updated version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion setGateDataset(String appVersionId, String datasetId) {
        AppVersion version = require(appVersionId);
        if (version.getStatus() != AppVersionStatus.DRAFT && version.getStatus() != AppVersionStatus.TESTING) {
            throw BizException.invalidParam("仅草稿或测试版可修改门禁评测集，当前状态 " + version.getStatus());
        }
        if (datasetId == null || datasetId.isBlank()) {
            version.setGateDatasetId(null);
        } else {
            EvalDataset dataset = evalDatasetService.require(datasetId);
            AppConfigSnapshot snapshot = parseConfig(version);
            if (snapshot.getKbId() != null && !dataset.getKbId().equals(snapshot.getKbId())) {
                throw BizException.invalidParam("门禁评测集所属知识库与应用配置的知识库不一致，无法用于双跑对比");
            }
            version.setGateDatasetId(datasetId);
        }
        appVersionMapper.updateById(version);
        log.info("application version gate data set bound, appVersionId={}, datasetId={}",
                appVersionId, version.getGateDatasetId());
        return version;
    }

    /**
     * Lists the versions of an application, newest first.
     *
     * @param appId application business id
     * @return versions
     */
    public List<AppVersion> listByApp(String appId) {
        return appVersionMapper.selectList(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppId, appId)
                .orderByDesc(AppVersion::getId));
    }

    /**
     * Loads a version or fails.
     *
     * @param appVersionId version business id
     * @return version
     */
    public AppVersion require(String appVersionId) {
        AppVersion version = appVersionMapper.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppVersionId, appVersionId)
                .last("limit 1"));
        if (version == null) {
            throw new BizException(ErrorCode.VERSION_NOT_FOUND, "application version not found");
        }
        return version;
    }

    /**
     * Newest version of an application, whatever its status.
     *
     * <p>What the console's chat preview runs against when the operator names no version: a preview is meant
     * to try the configuration currently being worked on, which is the newest row and not the released one.
     *
     * @param appId application business id
     * @return newest version
     */
    public AppVersion requireNewest(String appId) {
        AppVersion version = appVersionMapper.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppId, appId)
                .orderByDesc(AppVersion::getId)
                .last("limit 1"));
        if (version == null) {
            throw new BizException(ErrorCode.VERSION_NOT_FOUND, "应用尚未创建任何版本配置");
        }
        return version;
    }

    /**
     * Current released version of an application, {@code null} when none was released yet.
     *
     * @param appId application business id
     * @return released version or {@code null}
     */
    public AppVersion currentReleased(String appId) {
        return appVersionMapper.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppId, appId)
                .eq(AppVersion::getStatus, AppVersionStatus.RELEASED)
                .last("limit 1"));
    }

    /**
     * Moves a draft to the test version state, requirement section 4.7 "draft to test version".
     *
     * <p>This is where the configuration is completed and frozen: a field the operator never set is filled
     * from the knowledge base and then from the deployment defaults <em>now</em>, so the released snapshot is
     * self contained. Leaving nulls in it would mean a later change of a knowledge base default silently
     * changes the behaviour of a version the gate already validated.
     *
     * @param appVersionId version business id
     * @return updated version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion submitTest(String appVersionId) {
        AppVersion version = require(appVersionId);
        AppConfigSnapshot snapshot = materialize(parseConfig(version));
        version.setConfig(JsonUtil.toJson(snapshot));
        requireGateDatasetUsable(version, snapshot);
        transition(version, AppVersionStatus.TESTING);
        appVersionMapper.updateById(version);
        log.info("application version submitted for test, appVersionId={}, kbId={}",
                appVersionId, snapshot.getKbId());
        return version;
    }

    /**
     * Promotes a version to the released one and retires the previous released version.
     *
     * <p><b>Order matters.</b> The previous version is retired first and the candidate promoted second: the
     * schema enforces a single released version per application through a unique index, so promoting first
     * would collide with the row that is about to be retired. Both statements share one transaction, which is
     * what makes the switch atomic - requirement section 4.7 "release and rollback are atomic state
     * transitions".
     *
     * @param appVersionId version business id
     * @param forced       {@code true} records that an operator released despite a non passing verdict
     * @param operator     who performed the release, recorded with a forced release
     * @return promoted version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion promote(String appVersionId, boolean forced, String operator) {
        AppVersion candidate = require(appVersionId);
        AppVersion previous = currentReleased(candidate.getAppId());
        if (previous != null && previous.getAppVersionId().equals(appVersionId)) {
            throw BizException.invalidParam("该版本已经是当前正式版");
        }
        if (previous != null) {
            transition(previous, AppVersionStatus.SUPERSEDED);
            appVersionMapper.updateById(previous);
        }
        transition(candidate, AppVersionStatus.RELEASED);
        candidate.setForceReleased(forced ? FORCED : NOT_FORCED);
        candidate.setForceOperator(forced ? operator : null);
        candidate.setReleasedAt(LocalDateTime.now());
        appVersionMapper.updateById(candidate);
        log.info("application version released, appId={}, appVersionId={}, forced={}, superseded={}",
                candidate.getAppId(), appVersionId, forced,
                previous == null ? null : previous.getAppVersionId());
        return candidate;
    }

    /**
     * Rolls back to a historical version, requirement section 4.7 "rollback puts the target version back in
     * the released state".
     *
     * <p>The gate is not re-run: the target configuration was already released once, so its verdict is
     * historical fact, and a rollback is by definition an operator restoring a known state under pressure.
     *
     * @param appVersionId target version business id
     * @return restored version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion rollback(String appVersionId) {
        AppVersion target = require(appVersionId);
        if (target.getStatus() != AppVersionStatus.SUPERSEDED) {
            throw BizException.invalidParam("仅已下线版本可回滚为正式版，当前状态 " + target.getStatus());
        }
        return promote(appVersionId, target.forced(), target.getForceOperator());
    }

    /**
     * Records the outcome of a gate run on a version.
     *
     * @param version    version being gated, already loaded
     * @param status     gate result state
     * @param verdict    three state verdict literal holder
     * @param reason     classified reason
     * @param report     JSON report document
     * @param runIds     JSON array of the dual run ids
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordGate(AppVersion version, AppVersionStatus status, GateVerdict verdict,
                           GateReason reason, String report, String runIds) {
        transition(version, status);
        version.setGateVerdict(verdict);
        version.setGateReason(reason);
        version.setGateReport(report);
        version.setGateRunIds(runIds);
        appVersionMapper.updateById(version);
        log.info("application version gate recorded, appVersionId={}, verdict={}, reason={}",
                version.getAppVersionId(), verdict, reason);
    }

    /**
     * Moves a version into the gating state.
     *
     * @param version version being gated
     */
    @Transactional(rollbackFor = Exception.class)
    public void markGating(AppVersion version) {
        transition(version, AppVersionStatus.GATING);
        appVersionMapper.updateById(version);
    }

    /**
     * Resolves which version of an application serves one open API call, requirement section 4.8.
     *
     * <p>Three outcomes are deliberately distinct. An unknown or retired version is
     * {@code VERSION_NOT_FOUND}: from a caller's point of view a retired version is gone. A version that
     * exists but never reached a callable state is {@code VERSION_NOT_PUBLISHED}, so an agent integrating
     * against a draft learns it has to release it rather than that it mistyped an id. No version parameter
     * and no released version is the same {@code VERSION_NOT_PUBLISHED}, for the same reason.
     *
     * @param appId          application business id
     * @param versionLiteral requested version display literal, {@code null} routes to the released version
     * @return callable version
     */
    public AppVersion resolveForCall(String appId, String versionLiteral) {
        if (versionLiteral == null || versionLiteral.isBlank()) {
            AppVersion released = currentReleased(appId);
            if (released == null) {
                throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED,
                        "应用尚无正式版，请先发布后再调用");
            }
            return released;
        }
        AppVersion version = appVersionMapper.selectOne(new LambdaQueryWrapper<AppVersion>()
                .eq(AppVersion::getAppId, appId)
                .eq(AppVersion::getVersion, versionLiteral.trim())
                .last("limit 1"));
        if (version == null || version.getStatus() == AppVersionStatus.SUPERSEDED) {
            throw new BizException(ErrorCode.VERSION_NOT_FOUND,
                    "应用版本不存在或已下线：" + versionLiteral);
        }
        if (!version.getStatus().callable()) {
            throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED,
                    "应用版本尚未发布，当前状态 " + version.getStatus());
        }
        return version;
    }

    /**
     * Parses the configuration snapshot of a version.
     *
     * @param version version row
     * @return snapshot, never {@code null}
     */
    public AppConfigSnapshot parseConfig(AppVersion version) {
        AppConfigSnapshot snapshot = JsonUtil.parse(version.getConfig(), AppConfigSnapshot.class);
        return snapshot == null ? defaultSnapshot(null) : snapshot;
    }

    /**
     * Applies one state machine move, or refuses it.
     *
     * @param version version being moved
     * @param target  target status
     */
    private void transition(AppVersion version, AppVersionStatus target) {
        AppVersionStatus current = version.getStatus();
        if (!current.canTransitionTo(target)) {
            throw BizException.invalidParam("版本状态不允许该操作：" + current + " -> " + target);
        }
        version.setStatus(target);
    }

    /**
     * Fills every unset field of a snapshot, first from the knowledge base and then from the deployment
     * defaults, so the frozen configuration never falls back to a live value at call time.
     *
     * @param snapshot snapshot as the operator left it
     * @return the same instance, completed
     */
    private AppConfigSnapshot materialize(AppConfigSnapshot snapshot) {
        if (snapshot.getKbId() == null || snapshot.getKbId().isBlank()) {
            throw BizException.invalidParam("应用版本必须选择一个知识库");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(snapshot.getKbId());
        KbRetrievalConfig kbRetrieval = JsonUtil.parse(knowledgeBase.getRetrievalConfig(), KbRetrievalConfig.class);
        KbRetrievalConfig target = snapshot.retrievalOrDefaults();
        KbProperties.Retrieval defaults = properties.getRetrieval();

        target.setRecallTopK(firstNonNull(target.getRecallTopK(),
                kbRetrieval == null ? null : kbRetrieval.getRecallTopK(), defaults.getDefaultRecallTopK()));
        target.setTopN(firstNonNull(target.getTopN(),
                kbRetrieval == null ? null : kbRetrieval.getTopN(), defaults.getDefaultTopN()));
        target.setFusionMode(firstNonNull(target.getFusionMode(),
                kbRetrieval == null ? null : kbRetrieval.getFusionMode(), defaults.getFusionMode()));
        target.setWVec(firstNonNull(target.getWVec(),
                kbRetrieval == null ? null : kbRetrieval.getWVec(), defaults.getWVec()));
        target.setRrfK(firstNonNull(target.getRrfK(),
                kbRetrieval == null ? null : kbRetrieval.getRrfK(), defaults.getRrfK()));
        target.setRerankEnabled(firstNonNull(target.getRerankEnabled(),
                kbRetrieval == null ? null : kbRetrieval.getRerankEnabled(), defaults.isRerankEnabled()));
        target.setRewriteEnabled(firstNonNull(target.getRewriteEnabled(),
                kbRetrieval == null ? null : kbRetrieval.getRewriteEnabled(), defaults.isRewriteEnabled()));
        // score_threshold stays nullable on purpose: null is not "unset", it means no absolute filtering,
        // and substituting a default here would start filtering results the operator never asked to filter.
        if (target.getScoreThreshold() == null && kbRetrieval != null) {
            target.setScoreThreshold(kbRetrieval.getScoreThreshold());
        }
        snapshot.setRetrieval(target);
        if (snapshot.getPrompt() == null) {
            snapshot.setPrompt(AppPromptConfig.defaults());
        }
        return snapshot;
    }

    /**
     * Fast-fails a gate binding that could not measure what it claims to.
     *
     * <p>A data set belongs to one knowledge base. Binding one that measures a different corpus than the
     * application serves would produce a comparison whose numbers say nothing about the application - the
     * worst kind of gate, one that looks like it works.
     *
     * @param version  version being submitted
     * @param snapshot completed configuration snapshot
     */
    private void requireGateDatasetUsable(AppVersion version, AppConfigSnapshot snapshot) {
        if (version.getGateDatasetId() == null || version.getGateDatasetId().isBlank()) {
            return;
        }
        EvalDataset dataset = evalDatasetService.require(version.getGateDatasetId());
        if (!dataset.getKbId().equals(snapshot.getKbId())) {
            throw BizException.invalidParam("门禁评测集所属知识库与应用配置的知识库不一致，无法用于双跑对比");
        }
    }

    private AppVersion insertDraft(String appId, String version, AppConfigSnapshot snapshot,
                                   String gateDatasetId, String changelog) {
        AppVersion row = new AppVersion();
        row.setAppVersionId(bizIdGenerator.appVersionId());
        row.setAppId(appId);
        row.setVersion(version);
        row.setStatus(AppVersionStatus.DRAFT);
        row.setConfig(JsonUtil.toJson(snapshot));
        row.setGateDatasetId(gateDatasetId == null || gateDatasetId.isBlank() ? null : gateDatasetId);
        row.setChangelog(changelog);
        row.setForceReleased(NOT_FORCED);
        appVersionMapper.insert(row);
        return row;
    }

    /**
     * Next display version of an application: the highest major seen so far plus one.
     *
     * <p>Only the major part moves, which is what the requirement's {@code V1.0, V2.0} sequence describes; the
     * minor part exists so the column format can carry a patch level later without a migration.
     *
     * @param versions existing versions of the application
     * @return next display version literal
     */
    private String nextVersion(List<AppVersion> versions) {
        int highest = 0;
        for (AppVersion version : versions) {
            highest = Math.max(highest, majorOf(version.getVersion()));
        }
        return VERSION_PREFIX + (highest + 1) + VERSION_MINOR_SUFFIX;
    }

    private int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String digits = version.startsWith(VERSION_PREFIX) ? version.substring(VERSION_PREFIX.length()) : version;
        int dot = digits.indexOf('.');
        String major = dot < 0 ? digits : digits.substring(0, dot);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            // A version literal that is not of the expected shape cannot have been produced here; treating it
            // as zero keeps the sequence moving instead of failing every later creation.
            log.info("unexpected application version literal, ignored for numbering, version={}", version);
            return 0;
        }
    }

    /**
     * Snapshot of a brand new draft: the deployment defaults, so the console shows real values rather than
     * empty inputs.
     *
     * @param kbId knowledge base to point at, may be {@code null}
     * @return default snapshot
     */
    private AppConfigSnapshot defaultSnapshot(String kbId) {
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbId(kbId);
        KbProperties.Retrieval defaults = properties.getRetrieval();
        KbRetrievalConfig retrieval = new KbRetrievalConfig();
        retrieval.setRecallTopK(defaults.getDefaultRecallTopK());
        retrieval.setTopN(defaults.getDefaultTopN());
        retrieval.setFusionMode(defaults.getFusionMode());
        retrieval.setWVec(defaults.getWVec());
        retrieval.setRrfK(defaults.getRrfK());
        retrieval.setRerankEnabled(defaults.isRerankEnabled());
        retrieval.setRewriteEnabled(defaults.isRewriteEnabled());
        snapshot.setRetrieval(retrieval);
        snapshot.setPrompt(AppPromptConfig.defaults());
        return snapshot;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
