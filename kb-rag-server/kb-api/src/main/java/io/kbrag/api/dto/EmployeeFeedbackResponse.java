package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.quality.EmployeeFeedbackAccess;
import io.kbrag.app.quality.EmployeeFeedbackService;

import java.time.LocalDateTime;
import java.util.List;

/** 不输出员工身份、会话标识、内部配置或预签名地址。 */
public final class EmployeeFeedbackResponse {
    private EmployeeFeedbackResponse() { }

    public record Summary(@JsonProperty("run_id") String runId, String question,
                          @JsonProperty("feedback_note") String feedbackNote,
                          @JsonProperty("feedback_updated_at") LocalDateTime feedbackUpdatedAt,
                          @JsonProperty("content_restricted") boolean contentRestricted) {
        /** 受限行只保留不透明行标识，不能由表格提示泄露问题或反馈。 */
        public static Summary from(EmployeeFeedbackService.View view) {
            var run = view.readable();
            return new Summary(view.runId(), run == null ? null : run.getQuestion(),
                    run == null ? null : run.getFeedbackNote(), run == null ? null : run.getFeedbackUpdatedAt(), run == null);
        }
    }

    public record Detail(@JsonProperty("run_id") String runId, String question, String answer,
                         @JsonProperty("feedback_verdict") String feedbackVerdict,
                         @JsonProperty("feedback_note") String feedbackNote,
                         @JsonProperty("feedback_updated_at") LocalDateTime feedbackUpdatedAt,
                         @JsonProperty("app_version") String appVersion, List<Citation> citations) {
        /** 只投影已经过整轮证据授权的原始回答及评价。 */
        public static Detail from(EmployeeFeedbackAccess.Source source) {
            var run = source.run();
            return new Detail(run.getRunId(), run.getQuestion(), run.getAnswer(), run.getFeedbackVerdict().name(),
                    run.getFeedbackNote(), run.getFeedbackUpdatedAt(), source.appVersion(),
                    source.citations().stream().map(c -> new Citation(c.fileName(), c.content(), c.inherited())).toList());
        }
    }

    public record Citation(@JsonProperty("file_name") String fileName, String content, boolean inherited) { }
}
