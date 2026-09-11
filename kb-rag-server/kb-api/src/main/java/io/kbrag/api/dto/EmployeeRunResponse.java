package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.workspace.EmployeeConversationHistory.RunView;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.model.EmployeeCitation;

import java.time.LocalDateTime;
import java.util.List;

/** HTTP 与 SSE 使用相同的已提交、已授权运行快照。 */
public record EmployeeRunResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("turn_no") int turnNo, String question, String answer,
        List<CitationResponse> references, ConversationRunStatus status, ConversationRunStage stage,
        @JsonProperty("app_version_id") String appVersionId,
        @JsonProperty("app_version") String appVersion,
        @JsonProperty("snapshot_bound") boolean snapshotBound, int revision,
        @JsonProperty("checkpoint_seq") long checkpointSeq, boolean degraded,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage, boolean restricted,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("finished_at") LocalDateTime finishedAt) {

    /** 只能接收历史投影服务的安全视图，禁止直接序列化持久实体。 */
    public static EmployeeRunResponse from(RunView run) {
        return new EmployeeRunResponse(run.runId(), run.conversationId(), run.turnNo(), run.question(), run.answer(),
                run.references().stream().map(CitationResponse::from).toList(), run.status(), run.stage(),
                run.appVersionId(), run.appVersion(), run.snapshotBound(), run.revision(), run.checkpointSeq(),
                run.degraded(), run.errorCode(), run.errorMessage(), run.restricted(), run.createdAt(), run.finishedAt());
    }

    /** 页码仅来自原文件元数据；切片序号与自动生成的切片标题保持独立语义。 */
    public record CitationResponse(
            @JsonProperty("doc_id") String docId,
            @JsonProperty("document_version_id") String documentVersionId,
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("kb_id") String kbId,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("document_version") String documentVersion,
            @JsonProperty("document_updated_at") LocalDateTime documentUpdatedAt,
            @JsonProperty("version_created_at") LocalDateTime versionCreatedAt,
            @JsonProperty("chunk_title") String chunkTitle,
            @JsonProperty("page_no") Integer pageNo,
            @JsonProperty("chunk_ordinal") Integer chunkOrdinal, String content, boolean inherited) {

        private static CitationResponse from(EmployeeCitation citation) {
            return new CitationResponse(citation.docId(), citation.documentVersionId(), citation.chunkId(), citation.kbId(),
                    citation.fileName(), citation.documentVersion(), citation.documentUpdatedAt(), citation.versionCreatedAt(),
                    citation.chunkTitle(), citation.pageNo(), citation.chunkOrdinal(), citation.content(), citation.inherited());
        }
    }
}
