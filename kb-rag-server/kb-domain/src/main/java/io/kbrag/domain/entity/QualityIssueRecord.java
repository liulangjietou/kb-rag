package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.QualityIssueAction;
import lombok.Getter;
import lombok.Setter;

/** 随问题事务一起追加的处理记录，不允许覆盖既有说明。 */
@Getter
@Setter
@TableName("t_kb_quality_issue_record")
public class QualityIssueRecord extends BaseEntity {
    private String issueId;
    private String actorUserId;
    private String actorName;
    private QualityIssueAction action;
    private String note;
    private String caseId;
    private String runId;
}
