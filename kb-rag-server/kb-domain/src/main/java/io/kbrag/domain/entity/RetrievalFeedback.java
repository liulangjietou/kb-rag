package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.FeedbackChannel;
import io.kbrag.domain.enums.FeedbackStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One good/bad verdict on a debug page retrieval result, requirement section 4.5 "distilled into
 * evaluation material".
 *
 * <p>{@link #query} is stored raw, unlike the insight digest: converting this row into an evaluation
 * case replays the exact query, and a masked copy would create a case that never actually ran.
 *
 * <p>{@link #docId} is resolved server side from the chunk and may be {@code null}: feedback can
 * arrive after the chunk was deleted, and rejecting it then would lose the one signal the operator
 * still wanted recorded.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "query")
@TableName("t_kb_retrieval_feedback")
public class RetrievalFeedback extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed by the console. */
    @TableField("feedback_id")
    private String feedbackId;

    /** Knowledge base the query ran against. */
    @TableField("kb_id")
    private String kbId;

    /** Query the debug page ran, raw, replayed on conversion. */
    @TableField("query")
    private String query;

    /** Chunk the verdict concerns. */
    @TableField("chunk_id")
    private String chunkId;

    /** Owning document, {@code null} when the chunk was already deleted. */
    @TableField("doc_id")
    private String docId;

    /** Operator verdict. */
    @TableField("verdict")
    private FeedbackVerdict verdict;

    /** Lifecycle state, {@code NEW} until an operator converts or dismisses the row. */
    @TableField("status")
    private FeedbackStatus status;

    /** Evaluation case created from this row, {@code null} until converted. */
    @TableField("converted_case_id")
    private String convertedCaseId;

    /** Free form operator note; on the open API channel, the caller's comment truncated. */
    @TableField("note")
    private String note;

    /** Boundary the verdict arrived through, the M16 contract section 7. */
    @TableField("channel")
    private FeedbackChannel channel;

    /** Caller asserted end user identifier, open API channel only, not vouched for. */
    @TableField("end_user_id")
    private String endUserId;
}
