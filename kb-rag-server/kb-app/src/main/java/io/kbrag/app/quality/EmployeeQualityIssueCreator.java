package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.entity.QualityIssueRecord;
import io.kbrag.domain.enums.QualityIssueAction;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.mapper.QualityIssueRecordMapper;
import io.kbrag.domain.service.QueryDigestFactory;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Creates employee sourced quality issues without console operator permissions. */
@Component
@RequiredArgsConstructor
public class EmployeeQualityIssueCreator {

    private static final String ISSUE_ID_PREFIX = "kqi_";
    private static final int SUMMARY_LIMIT = 256;

    private final KnowledgeQualityIssueMapper issues;
    private final QualityIssueRecordMapper records;
    private final QueryDigestFactory digests;

    @Transactional(rollbackFor = Exception.class)
    public void createIfAbsent(EmployeeConversationRun run, String kbId, String appVersionId) {
        KnowledgeQualityIssue existing = issues.selectOne(new LambdaQueryWrapper<KnowledgeQualityIssue>()
                .eq(KnowledgeQualityIssue::getKbId, kbId)
                .eq(KnowledgeQualityIssue::getSourceType, QualityIssueSource.EMPLOYEE_ANSWER)
                .eq(KnowledgeQualityIssue::getSourceId, run.getRunId()));
        if (existing != null) {
            return;
        }
        KnowledgeQualityIssue issue = KnowledgeQualityIssue.open(
                ISSUE_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                kbId, QualityIssueSource.EMPLOYEE_ANSWER, run.getRunId(),
                StringUtils.defaultString(digests.digest(run.getQuestion(), SUMMARY_LIMIT)));
        issue.setAffectedAppVersionId(appVersionId);
        try {
            if (issues.insert(issue) != 1) {
                throw new IllegalStateException("failed to insert quality issue");
            }
        } catch (DuplicateKeyException ignored) {
            return;
        }
        QualityIssueRecord record = new QualityIssueRecord();
        record.setIssueId(issue.getIssueId());
        record.setActorUserId("system");
        record.setActorName("系统自动");
        record.setAction(QualityIssueAction.CREATED);
        records.insert(record);
    }
}