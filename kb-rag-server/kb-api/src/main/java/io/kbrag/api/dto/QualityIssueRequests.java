package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalCaseCommand;
import io.kbrag.app.quality.KnowledgeQualityIssueService;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 质量流程的有限输入形状，长度和格式在 HTTP 边界统一校验。 */
public final class QualityIssueRequests {
    private QualityIssueRequests() { }

    public record Create(@JsonProperty("source_type") @NotNull QualityIssueSource sourceType,
                         @JsonProperty("source_id") @NotBlank @Size(max = 64) String sourceId) { }

    public record Revision(@NotNull @Min(0) Integer revision) { }

    public record Note(@NotNull @Min(0) Integer revision, @NotBlank @Size(max = 2048) String note) { }

    public record Resolve(@NotNull @Min(0) Integer revision, @JsonProperty("run_id") @NotBlank @Size(max = 64) String runId,
                          @NotBlank @Size(max = 2048) String note) { }

    public record Correction(@NotNull @Min(0) Integer revision, @NotNull QualityIssueReason reason,
                             @JsonProperty("case_revision") @Min(0) Integer caseRevision,
                             @JsonProperty("dataset_id") @NotBlank @Size(max = 64) String datasetId,
                             @JsonProperty("affected_app_version_id") @NotBlank @Size(max = 64) String affectedAppVersionId,
                             @NotNull @Valid CaseInput input) {
        /** 映射应用命令，资料所属关系与当前权限由服务核验。 */
        public KnowledgeQualityIssueService.Correction toCommand() {
            return new KnowledgeQualityIssueService.Correction(revision, caseRevision, reason, datasetId, affectedAppVersionId, input.toCommand());
        }
    }

    public record CaseInput(@NotBlank @Size(max = 4096) String query,
                            @Size(max = 20) List<@NotNull @Valid Message> messages,
                            @JsonProperty("expected_answer") @Size(max = 8192) String expectedAnswer,
                            @JsonProperty("expected_refusal") @NotNull Boolean expectedRefusal,
                            @JsonProperty("anchor_type") @NotNull AnchorType anchorType,
                            @NotNull @Size(max = 20) List<@NotNull @Valid Evidence> evidences,
                            @NotBlank @Size(max = 1024) String note) {
        /** 拒答的空证据列表保持为空，不能通过错误分片补足。 */
        public EvalCaseCommand toCommand() {
            return EvalCaseCommand.builder().query(query).messages(messages == null ? List.of()
                            : messages.stream().map(message -> new ChatMessage(message.role(), message.content())).toList())
                    .expectedAnswer(expectedAnswer).expectedRefusal(expectedRefusal).anchorType(anchorType)
                    .evidences(evidences.stream().map(Evidence::toEvidence).toList()).note(note.trim()).build();
        }
    }

    public record Message(@NotNull @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 4096) String content) { }

    public record Evidence(@JsonProperty("doc_id") @NotBlank @Size(max = 64) String docId,
                           @Size(max = 8192) String span) {
        /** 版本标注由用例服务填写，客户端不提供。 */
        public EvalEvidence toEvidence() {
            EvalEvidence evidence = new EvalEvidence();
            evidence.setDocId(docId); evidence.setSpan(span);
            return evidence;
        }
    }
}
