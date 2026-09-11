package io.kbrag.domain.entity;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.QualityIssueStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 负责人、修订号和问题阶段边界。 */
class KnowledgeQualityIssueTest {
    @Test
    void ownershipAndRevisionPreventStaleOrUnassignedWrites() {
        var issue = issue();
        assertThrows(BizException.class, () -> issue.requireOwned("u1", 0));
        issue.claim("u1", "处理人", 0);
        assertEquals(QualityIssueStatus.IN_PROGRESS, issue.getStatus());
        assertThrows(BizException.class, () -> issue.claim("u2", "他人", 0));
        assertThrows(BizException.class, () -> issue.release("u2", 0));
        issue.setLockVersion(1);
        assertEquals(ErrorCode.QUALITY_ISSUE_CONFLICT, assertThrows(BizException.class, () -> issue.release("u1", 0)).getErrorCode());
    }

    @Test
    void correctionSurvivesReleaseAndReopeningButResolutionMarkersDoNot() {
        var issue = issue(); issue.claim("u1", "处理人", 0);
        assertThrows(BizException.class, () -> issue.resolved("u1", 0, "run", "av", LocalDateTime.now()));
        issue.corrected("u1", 0, QualityIssueReason.RETRIEVAL_MISS, "ds", "case", "input", "av_old");
        issue.release("u1", 0); issue.claim("u2", "新处理人", 0);
        assertEquals(QualityIssueStatus.WAITING_REGRESSION, issue.getStatus());
        assertThrows(BizException.class, () -> issue.corrected("u2", 0, QualityIssueReason.OTHER, "different_ds", "case", "input", "av"));
        issue.resolved("u2", 0, "run", "av_new", LocalDateTime.now());
        assertThrows(BizException.class, () -> issue.release("u2", 0));
        issue.reopen(0);
        assertEquals("case", issue.getCaseId()); assertNull(issue.getVerifiedRunId());
        assertNull(issue.getResolvedAt()); assertNull(issue.getOwnerUserId());
    }

    private KnowledgeQualityIssue issue() {
        return KnowledgeQualityIssue.open("issue", "kb", QualityIssueSource.ZERO_HIT, "hash", "摘要");
    }
}
