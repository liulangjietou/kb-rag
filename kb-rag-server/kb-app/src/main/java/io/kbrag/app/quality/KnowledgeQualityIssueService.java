package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.eval.EvalCaseCommand;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.entity.QualityIssueRecord;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.enums.QualityIssueAction;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.QualityIssueStatus;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.mapper.QualityIssueRecordMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.model.EvalCaseInput;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.QueryDigestFactory;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Collection;

/** 质量问题跨反馈、用例和回归的事务编排；阶段约束归实体，资料权限归专用访问组件。 */
@Service
@RequiredArgsConstructor
public class KnowledgeQualityIssueService {
    private static final int SUMMARY_LIMIT = 256;
    private static final int RECORD_PAGE_SIZE = 50;
    private static final String ISSUE_ID_PREFIX = "kqi_";

    private final KnowledgeQualityIssueMapper issues;
    private final QualityIssueRecordMapper records;
    private final RetrievalFeedbackMapper feedback;
    private final SearchInsightMapper insights;
    private final EvalDatasetService datasets;
    private final QualityIssueAccess access;
    private final QualityRegressionVerifier regression;
    private final QueryDigestFactory digests;

    /** 来源必须是当前知识库中真实的 BAD 或零命中信号，同一来源重复操作返回已有问题。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue create(String kbId, QualityIssueSource source, String sourceId) {
        access.requireKb(kbId);
        SearchInsight insight = null;
        String deduplicationId = sourceId;
        if (source == QualityIssueSource.ZERO_HIT) {
            // 客户端只使用随机的洞察 ID；查询哈希仅用于服务端去重，不进入接口响应。
            insight = insights.selectOne(new LambdaQueryWrapper<SearchInsight>()
                    .eq(SearchInsight::getKbId, kbId).eq(SearchInsight::getInsightId, sourceId)
                    .eq(SearchInsight::getZeroHit, true));
            if (insight == null) throw BizException.notFound("zero-hit insight not found");
            deduplicationId = insight.getQueryHash();
        }
        KnowledgeQualityIssue existing = issues.selectOne(new LambdaQueryWrapper<KnowledgeQualityIssue>()
                .eq(KnowledgeQualityIssue::getKbId, kbId).eq(KnowledgeQualityIssue::getSourceType, source)
                .eq(KnowledgeQualityIssue::getSourceId, deduplicationId));
        if (existing != null) {
            access.requireContent(existing);
            return existing;
        }
        KnowledgeQualityIssue issue;
        if (source == QualityIssueSource.BAD_FEEDBACK) {
            RetrievalFeedback original = feedback.selectOne(new LambdaQueryWrapper<RetrievalFeedback>()
                    .eq(RetrievalFeedback::getKbId, kbId).eq(RetrievalFeedback::getFeedbackId, sourceId));
            if (original == null) throw BizException.notFound("feedback not found");
            if (original.getVerdict() != FeedbackVerdict.BAD) throw BizException.invalidParam("只有 BAD 反馈可建立质量问题");
            if (original.getDocId() == null) throw BizException.invalidParam("原反馈资料已无法定位，请从零命中报告或其他有效反馈建立问题");
            access.requireDocument(kbId, original.getDocId());
            issue = newIssue(kbId, source, sourceId, original.getQuery());
            issue.setSourceDocId(original.getDocId());
        } else {
            issue = newIssue(kbId, source, deduplicationId, insight.getQueryDigest());
        }
        try { requireWritten(issues.insert(issue)); }
        catch (DuplicateKeyException conflict) { throw conflict(); }
        record(issue, QualityIssueAction.CREATED, null, null);
        return issue;
    }

    /** 列表包含受限条目的状态；内容必须由接口层按 readable 标记投影。 */
    public IPage<IssueView> list(String kbId, QualityIssueStatus status, boolean mine, long page, long size) {
        access.requireKb(kbId);
        var query = new LambdaQueryWrapper<KnowledgeQualityIssue>().eq(KnowledgeQualityIssue::getKbId, kbId)
                .eq(status != null, KnowledgeQualityIssue::getStatus, status)
                .eq(mine, KnowledgeQualityIssue::getOwnerUserId, AccessGuard.currentUser().userId())
                .orderByDesc(KnowledgeQualityIssue::getId);
        return issues.selectPage(new Page<>(page, size), query)
                .convert(issue -> new IssueView(issue, access.canReadContent(issue)));
    }

    /** 详情在当前授权通过后返回；受限列表行不能借直接链接读取正文。 */
    public Detail detail(String kbId, String issueId) {
        var issue = require(kbId, issueId);
        return new Detail(issue, issue.getCaseId() == null ? null : access.readCase(issue));
    }

    /** 处理记录分页并复用问题内容权限，避免旧记录绕过资料撤权。 */
    public IPage<QualityIssueRecord> history(String kbId, String issueId, long page) {
        require(kbId, issueId);
        return records.selectPage(new Page<>(page, RECORD_PAGE_SIZE), new LambdaQueryWrapper<QualityIssueRecord>()
                .eq(QualityIssueRecord::getIssueId, issueId).orderByDesc(QualityIssueRecord::getId));
    }

    /** 领取和释放使用同一修订号锁，处理记录与状态一起提交。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue claim(String kbId, String issueId, int revision) {
        var issue = require(kbId, issueId);
        var actor = AccessGuard.currentUser();
        issue.claim(actor.userId(), displayName(), revision);
        persist(issue, QualityIssueAction.CLAIMED, null, null);
        return issue;
    }

    /** 当前负责人可释放问题，已有纠正用例和处理说明不会丢失。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue release(String kbId, String issueId, int revision) {
        var issue = require(kbId, issueId);
        issue.release(AccessGuard.currentUser().userId(), revision);
        persist(issue, QualityIssueAction.RELEASED, null, null);
        return issue;
    }

    /** 人工说明追加保存，冲突时回滚，不覆盖他人的处理记录。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue addNote(String kbId, String issueId, int revision, String note) {
        var issue = require(kbId, issueId);
        issue.requireOwned(AccessGuard.currentUser().userId(), revision);
        persist(issue, QualityIssueAction.NOTE_ADDED, note, null);
        return issue;
    }

    /** 人工确认问题和正确依据后写入现有评测集，后续纠正继续修订同一用例。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue correct(String kbId, String issueId, Correction command) {
        AccessGuard.requirePermission(PermissionCodes.EVAL_WRITE);
        AccessGuard.requirePermission(PermissionCodes.EVAL_READ);
        AccessGuard.requirePermission(PermissionCodes.APP_READ);
        var issue = require(kbId, issueId);
        String userId = AccessGuard.currentUser().userId();
        issue.requireOwned(userId, command.revision());
        if (!Objects.equals(kbId, datasets.require(command.datasetId()).getKbId())) {
            throw BizException.notFound("evaluation dataset not found");
        }
        if (issue.getDatasetId() != null && !Objects.equals(issue.getDatasetId(), command.datasetId())) {
            throw BizException.invalidParam("已有纠正用例的所属评测集不能变更");
        }
        var affectedVersion = access.requireVersion(kbId, command.affectedAppVersionId());
        if (issue.getAffectedAppVersionId() != null && !Objects.equals(affectedVersion.getAppId(),
                access.requireVersion(kbId, issue.getAffectedAppVersionId()).getAppId())) {
            throw BizException.invalidParam("已有问题的受影响应用不能更换，可在同一应用中选择其他版本");
        }
        access.requireEvidence(kbId, command.input().getEvidences());
        if (!command.input().isExpectedRefusal() && StringUtils.isBlank(command.input().getExpectedAnswer())) {
            throw BizException.invalidParam("正常回答必须填写标准答案，才能验证本次纠正是否有效");
        }
        if (issue.getCaseId() != null) {
            EvalCase current = access.lockCase(issue);
            if (!Objects.equals(current.getLockVersion(), command.caseRevision())) {
                throw new BizException(ErrorCode.EVAL_DATASET_CONFLICT, "关联用例已在评测集页面更新，请加载最新内容后重新确认纠正");
            }
            List<EvalEvidence> previous = JsonUtil.parse(current.getEvidences(), new TypeReference<List<EvalEvidence>>() { });
            protectDocuments(issue, previous.stream().map(EvalEvidence::getDocId).toList());
        }
        EvalCase corrected = issue.getCaseId() == null ? datasets.createCase(command.datasetId(), command.input())
                : datasets.updateCase(issue.getCaseId(), command.input());
        issue.corrected(userId, command.revision(), command.reason(), command.datasetId(), corrected.getCaseId(),
                JsonUtil.toJson(EvalCaseInput.capture(corrected)), command.affectedAppVersionId());
        protectDocuments(issue, command.input().getEvidences().stream().map(EvalEvidence::getDocId).toList());
        persist(issue, QualityIssueAction.CORRECTED, command.input().getNote(), null);
        return issue;
    }

    /** 选择已完成的真实运行后核验，不在本事务内启动后台评测。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue resolve(String kbId, String issueId, int revision, String runId, String note) {
        AccessGuard.requirePermission(PermissionCodes.EVAL_READ);
        AccessGuard.requirePermission(PermissionCodes.APP_READ);
        var issue = require(kbId, issueId);
        String userId = AccessGuard.currentUser().userId();
        issue.requireOwned(userId, revision);
        if (issue.getStatus() != QualityIssueStatus.WAITING_REGRESSION) throw BizException.invalidParam("请先保存纠正用例");
        var verified = regression.verify(issue, runId);
        protectDocuments(issue, verified.protectedDocIds());
        issue.resolved(userId, revision, runId, verified.appVersionId(), LocalDateTime.now());
        persist(issue, QualityIssueAction.RESOLVED, note, runId);
        return issue;
    }

    /** 人工确认前只查看关联用例的合格结果，不展示整批运行中其他用例的正文。 */
    @Transactional(rollbackFor = Exception.class)
    public QualityRegressionVerifier.Verification previewRegression(String kbId, String issueId, String runId) {
        AccessGuard.requirePermission(PermissionCodes.EVAL_READ);
        AccessGuard.requirePermission(PermissionCodes.APP_READ);
        var issue = require(kbId, issueId);
        if (issue.getExpectedCaseInput() == null) throw BizException.invalidParam("请先保存纠正用例");
        return regression.verify(issue, runId);
    }

    /** 重新出现的问题可重新打开，同时保留上一轮回归记录。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQualityIssue reopen(String kbId, String issueId, int revision, String note) {
        var issue = require(kbId, issueId);
        issue.reopen(revision);
        persist(issue, QualityIssueAction.REOPENED, note, null);
        return issue;
    }

    private KnowledgeQualityIssue require(String kbId, String issueId) {
        access.requireKb(kbId);
        var issue = issues.selectOne(new LambdaQueryWrapper<KnowledgeQualityIssue>()
                .eq(KnowledgeQualityIssue::getKbId, kbId).eq(KnowledgeQualityIssue::getIssueId, issueId));
        if (issue == null) throw BizException.notFound("quality issue not found");
        access.requireContent(issue);
        return issue;
    }

    private KnowledgeQualityIssue newIssue(String kbId, QualityIssueSource source, String sourceId, String summary) {
        return KnowledgeQualityIssue.open(ISSUE_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""), kbId,
                source, sourceId, StringUtils.defaultString(digests.digest(summary, SUMMARY_LIMIT)));
    }

    private void protectDocuments(KnowledgeQualityIssue issue, Collection<String> additions) {
        var docIds = new LinkedHashSet<String>();
        if (issue.getProtectedDocIds() != null) {
            docIds.addAll(JsonUtil.parse(issue.getProtectedDocIds(), new TypeReference<List<String>>() { }));
        }
        docIds.addAll(additions);
        issue.setProtectedDocIds(JsonUtil.toJson(docIds));
    }

    private void persist(KnowledgeQualityIssue issue, QualityIssueAction action, String note, String runId) {
        // 已加载实体带有旧审计值，明确记录本次处理时间，保留原创建时间。
        issue.setUpdatedAt(LocalDateTime.now());
        requireWritten(issues.updateById(issue));
        record(issue, action, note, runId);
    }

    private void record(KnowledgeQualityIssue issue, QualityIssueAction action, String note, String runId) {
        QualityIssueRecord record = new QualityIssueRecord();
        record.setIssueId(issue.getIssueId());
        record.setActorUserId(AccessGuard.currentUser().userId());
        record.setActorName(displayName());
        record.setAction(action);
        record.setNote(note);
        record.setCaseId(issue.getCaseId());
        record.setRunId(runId);
        requireWritten(records.insert(record));
    }

    private String displayName() {
        var actor = AccessGuard.currentUser();
        return StringUtils.left(StringUtils.defaultIfBlank(actor.displayName(), actor.username()), 128);
    }

    private void requireWritten(int count) { if (count != 1) throw conflict(); }

    private BizException conflict() {
        return new BizException(ErrorCode.QUALITY_ISSUE_CONFLICT, "质量问题已更新，请读取最新记录后重试");
    }

    /** 内部投影，接口只输出经过权限裁剪的字段。 */
    public record IssueView(KnowledgeQualityIssue issue, boolean readable) { }

    /** 当前关联用例仅供纠正表单展示，问题保存的已确认快照仍用于回归核验。 */
    public record Detail(KnowledgeQualityIssue issue, EvalCase currentCase) { }

    /** 输入格式在 API 入口校验，跨资源关系由当前服务校验。 */
    public record Correction(int revision, Integer caseRevision, QualityIssueReason reason, String datasetId,
                             String affectedAppVersionId, EvalCaseCommand input) { }
}
