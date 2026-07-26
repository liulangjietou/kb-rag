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
import io.kbrag.domain.model.AppRoutingConfig;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphFusionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /** Index of the first declared knowledge base, whose defaults complete an unset parameter. */
    private static final int PRIMARY = 0;

    private final AppVersionMapper appVersionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EvalDatasetService evalDatasetService;
    private final BizIdGenerator bizIdGenerator;
    private final GraphFusionPolicy graphFusionPolicy;
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
        if (snapshot != null) {
            // Validated where the operator's input enters the system, not where it is later read: a draft
            // that stored fifteen plus one bases would fail at submit time with the form long gone.
            requireUsableKbRefs(effective.getKbRefs());
        }
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
            List<String> kbIds = parseConfig(version).kbIds();
            if (!CollectionUtils.isEmpty(kbIds) && !kbIds.contains(dataset.getKbId())) {
                throw BizException.invalidParam("门禁评测集所属知识库不在应用关联的知识库范围内，无法用于双跑对比");
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
        log.info("application version submitted for test, appVersionId={}, kbIds={}",
                appVersionId, snapshot.kbIds());
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
        return promote(appVersionId, forced, operator, null);
    }

    /**
     * Promotes a version to the released one, optionally installing the index snapshot it was frozen with.
     *
     * <p><b>Why the snapshot arrives as a parameter.</b> Creating it talks to search engines and can take
     * minutes on the Milvus path, and this method owns a transaction that holds the unique released slot.
     * Doing the copy inside would keep that lock for the duration of the copy and would make a failed copy
     * indistinguishable from a failed state transition. So the copy happens first, outside, and what reaches
     * here is the finished result - requirement section 4.7 "the snapshot is created before the version takes
     * effect".
     *
     * <p>A {@code null} snapshot leaves both columns as they are, which is what a rollback needs: the target
     * version already carries the snapshot of its own release and must serve exactly that corpus again.
     *
     * @param appVersionId version business id
     * @param forced       {@code true} records that an operator released despite a non passing verdict
     * @param operator     who performed the release, recorded with a forced release
     * @param snapshot     frozen index snapshot and visibility set, {@code null} keeps the existing columns
     * @return promoted version
     */
    @Transactional(rollbackFor = Exception.class)
    public AppVersion promote(String appVersionId, boolean forced, String operator,
                              AppReleaseSnapshotService.ReleaseSnapshot snapshot) {
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
        if (snapshot != null) {
            candidate.setIndexSnapshots(JsonUtil.toJson(snapshot.indexSnapshots()));
            candidate.setVisibleVersionIds(JsonUtil.toJson(snapshot.visibleVersionIds()));
        }
        appVersionMapper.updateById(candidate);
        log.info("application version released, appId={}, appVersionId={}, forced={}, superseded={}, "
                        + "snapshotIndexes={}",
                candidate.getAppId(), appVersionId, forced,
                previous == null ? null : previous.getAppVersionId(),
                snapshot == null ? 0 : snapshot.indexSnapshots().size());
        return candidate;
    }

    /**
     * Rolls back to a historical version, requirement section 4.7 "rollback puts the target version back in
     * the released state".
     *
     * <p>The gate is not re-run: the target configuration was already released once, so its verdict is
     * historical fact, and a rollback is by definition an operator restoring a known state under pressure.
     *
     * <p><b>No new index snapshot either</b>, requirement section 4.7 "rollback restores the historical
     * knowledge state". The target keeps the snapshot of its own release, which is the entire mechanism by
     * which a rollback restores what the corpus looked like then; snapshotting the live index here would roll
     * the configuration back onto today's knowledge and defeat the purpose.
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
        List<KbRef> kbRefs = requireUsableKbRefs(snapshot.getKbRefs());
        // Written back in the M5 shape, which is also how a snapshot frozen from a legacy single kb_id row
        // stops being legacy: the freeze is the one moment a snapshot may legitimately be rewritten.
        snapshot.setKbRefs(kbRefs);
        if (snapshot.getRouting() == null) {
            snapshot.setRouting(AppRoutingConfig.defaults());
        }
        // The first declared base completes the retrieval parameters the operator left unset, the same base
        // the retrieval pipeline resolves its knowledge base layer from.
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbRefs.get(PRIMARY).kbId());
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
        // The graph switch is a knowledge base property, never an application one: it decides what was
        // extracted, not how a call is scored. It is copied into the snapshot so the frozen configuration
        // records which routes the release gate measured, and so the very same mutual exclusion check the
        // knowledge base write goes through also refuses a release that would fuse three routes by weight.
        target.setGraphEnabled(kbRetrieval != null && kbRetrieval.graphEnabled());
        graphFusionPolicy.requireCompatible(target);
        snapshot.setRetrieval(target);
        if (snapshot.getPrompt() == null) {
            snapshot.setPrompt(AppPromptConfig.defaults());
        }
        return snapshot;
    }

    /**
     * Fast-fails a gate binding that could not measure what it claims to.
     *
     * <p>A data set belongs to one knowledge base. Binding one that measures a corpus the application does
     * not serve at all would produce a comparison whose numbers say nothing about the application - the
     * worst kind of gate, one that looks like it works. A multi base application is therefore gated on one
     * of the bases it serves: the dual run stays single base evaluation (requirement section 4.6), and
     * requiring the data set to cover every linked base would make the gate impossible to satisfy.
     *
     * @param version  version being submitted
     * @param snapshot completed configuration snapshot
     */
    private void requireGateDatasetUsable(AppVersion version, AppConfigSnapshot snapshot) {
        if (version.getGateDatasetId() == null || version.getGateDatasetId().isBlank()) {
            return;
        }
        EvalDataset dataset = evalDatasetService.require(version.getGateDatasetId());
        if (!snapshot.kbIds().contains(dataset.getKbId())) {
            throw BizException.invalidParam("门禁评测集所属知识库不在应用关联的知识库范围内，无法用于双跑对比");
        }
    }

    /**
     * Validates the knowledge base links of a version, requirement section 4.7.
     *
     * <p>The single authoritative check of the four rules the console form also enforces: at least one base
     * and at most the configured maximum, no base linked twice, a positive weight, and a base that actually
     * exists. The console cannot be the authority - an API client bypasses it entirely - and putting the
     * check anywhere later would accept a configuration the release gate would then measure.
     *
     * @param kbRefs references as the operator left them, already weight normalised by the snapshot
     * @return the same references, validated
     */
    private List<KbRef> requireUsableKbRefs(List<KbRef> kbRefs) {
        if (CollectionUtils.isEmpty(kbRefs)) {
            throw BizException.invalidParam("应用版本必须关联至少一个知识库");
        }
        int maximum = properties.getRetrieval().getMaxLinkedKb();
        if (kbRefs.size() > maximum) {
            throw BizException.invalidParam("应用版本关联的知识库不能超过 " + maximum + " 个，当前 " + kbRefs.size());
        }
        Set<String> seen = new LinkedHashSet<>(kbRefs.size());
        for (KbRef ref : kbRefs) {
            if (!seen.add(ref.kbId())) {
                throw BizException.invalidParam("应用版本重复关联同一个知识库：" + ref.kbId());
            }
            if (ref.weight() == null || ref.weight() < KbRef.DEFAULT_WEIGHT) {
                throw BizException.invalidParam("知识库配额权重必须为正整数：" + ref.kbId());
            }
            knowledgeBaseService.require(ref.kbId());
        }
        return kbRefs;
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
        snapshot.setKbRefs(kbId == null || kbId.isBlank() ? List.of() : List.of(KbRef.of(kbId)));
        snapshot.setRouting(AppRoutingConfig.defaults());
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
