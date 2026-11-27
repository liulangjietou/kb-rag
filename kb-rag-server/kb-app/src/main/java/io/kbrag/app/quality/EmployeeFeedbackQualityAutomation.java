package io.kbrag.app.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EmployeeRunTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeFeedbackQualityAutomation {

    private final QualityAutomationConfigService automationConfigService;
    private final EmployeeQualityIssueCreator issueCreator;

    public void onFeedbackSaved(EmployeeConversationRun run) {
        if (run.getFeedbackVerdict() != FeedbackVerdict.BAD
                || !automationConfigService.autoIssueFromEmployeeBadEnabled()) {
            return;
        }
        EmployeeRunTarget target = JsonUtil.parse(run.getTargetJson(), EmployeeRunTarget.class);
        AppConfigSnapshot snapshot = JsonUtil.parse(target.config(), AppConfigSnapshot.class);
        for (String kbId : snapshot.kbIds()) {
            try {
                issueCreator.createIfAbsent(run, kbId, target.appVersionId());
            } catch (RuntimeException failure) {
                // 反馈已先行提交，单个知识库建单失败不应影响反馈结果和其他知识库，记录 error 与异常栈后继续
                log.error("auto employee quality issue creation failed, errorCode={}, kbId={}, runId={}",
                        ErrorCode.INTERNAL_ERROR, kbId, run.getRunId(), failure);
            }
        }
    }
}