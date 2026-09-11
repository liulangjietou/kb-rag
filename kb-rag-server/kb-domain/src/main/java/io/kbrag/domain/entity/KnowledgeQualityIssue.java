package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.QualityIssueStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

/** 以知识库为授权根的质量问题，身份和阶段变化由聚合自身约束。 */
@Getter
@Setter
@ToString(exclude = {"summary", "expectedCaseInput"})
@TableName("t_kb_quality_issue")
public class KnowledgeQualityIssue extends BaseEntity {
    private String issueId;
    private String kbId;
    private QualityIssueSource sourceType;
    private String sourceId;
    private String sourceDocId;
    /** 历次纠正涉及的全部文档，旧处理记录继续服从原资料权限。 */
    private String protectedDocIds;
    private String summary;
    private QualityIssueStatus status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String ownerUserId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String ownerName;
    private QualityIssueReason reason;
    private String datasetId;
    private String caseId;
    private String expectedCaseInput;
    private String affectedAppVersionId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String verifiedRunId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String verifiedAppVersionId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resolvedAt;

    /** 来源完成真实性与权限校验后，创建尚未分派的问题。 */
    public static KnowledgeQualityIssue open(String issueId, String kbId, QualityIssueSource source,
                                              String sourceId, String summary) {
        KnowledgeQualityIssue issue = new KnowledgeQualityIssue();
        issue.setIssueId(issueId);
        issue.setKbId(kbId);
        issue.setSourceType(source);
        issue.setSourceId(sourceId);
        issue.setSummary(summary);
        issue.setStatus(QualityIssueStatus.NEW);
        issue.setLockVersion(0);
        return issue;
    }

    /** 自领已有未分派问题，防止不同页面互相覆盖负责人。 */
    public void claim(String userId, String displayName, int revision) {
        requireRevision(revision);
        if (status != QualityIssueStatus.NEW || ownerUserId != null) {
            throw BizException.invalidParam("此问题已分派或已解决，请刷新后查看处理状态");
        }
        ownerUserId = userId;
        ownerName = displayName;
        status = caseId == null ? QualityIssueStatus.IN_PROGRESS : QualityIssueStatus.WAITING_REGRESSION;
    }

    /** 负责人释放问题后，后续领取者仍能看到此前纠正用例。 */
    public void release(String userId, int revision) {
        requireOwned(userId, revision);
        status = QualityIssueStatus.NEW;
        ownerUserId = null;
        ownerName = null;
    }

    /** 只有当前负责人能修改处理信息，解决后的问题必须先重新打开。 */
    public void requireOwned(String userId, int revision) {
        requireRevision(revision);
        if (!Objects.equals(ownerUserId, userId)) {
            throw BizException.forbidden("请先领取该问题，或由当前负责人处理");
        }
        if (status == QualityIssueStatus.RESOLVED) {
            throw BizException.invalidParam("问题已解决，请先重新打开");
        }
    }

    /** 保存人工纠正的关联，不自动把 BAD 分片当作正确证据。 */
    public void corrected(String userId, int revision, QualityIssueReason reason, String datasetId,
                          String caseId, String expectedCaseInput, String affectedVersionId) {
        requireOwned(userId, revision);
        if (this.datasetId != null && !Objects.equals(this.datasetId, datasetId)) {
            throw BizException.invalidParam("已有纠正用例的所属评测集不能变更");
        }
        this.reason = reason;
        this.datasetId = datasetId;
        this.caseId = caseId;
        this.expectedCaseInput = expectedCaseInput;
        this.affectedAppVersionId = affectedVersionId;
        status = QualityIssueStatus.WAITING_REGRESSION;
        verifiedRunId = null;
        verifiedAppVersionId = null;
        resolvedAt = null;
    }

    /** 应用服务核验具体回归结果并取得人工说明后才允许进入解决状态。 */
    public void resolved(String userId, int revision, String runId, String versionId, LocalDateTime now) {
        requireOwned(userId, revision);
        if (status != QualityIssueStatus.WAITING_REGRESSION) {
            throw BizException.invalidParam("请先补齐纠正用例并完成回归");
        }
        status = QualityIssueStatus.RESOLVED;
        verifiedRunId = runId;
        verifiedAppVersionId = versionId;
        resolvedAt = now;
    }

    /** 再次发现问题时保留既有处理记录，清除上一轮解决标记并重新分派。 */
    public void reopen(int revision) {
        requireRevision(revision);
        if (status != QualityIssueStatus.RESOLVED) {
            throw BizException.invalidParam("只有已解决的问题可以重新打开");
        }
        status = QualityIssueStatus.NEW;
        ownerUserId = null;
        ownerName = null;
        verifiedRunId = null;
        verifiedAppVersionId = null;
        resolvedAt = null;
    }

    private void requireRevision(int revision) {
        if (getLockVersion() != revision) {
            throw new BizException(ErrorCode.QUALITY_ISSUE_CONFLICT, "问题已被其他页面更新，请读取最新记录后重试");
        }
    }
}
