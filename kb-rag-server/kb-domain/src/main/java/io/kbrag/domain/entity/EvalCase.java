package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseSource;
import io.kbrag.domain.enums.CaseStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One query and evidence pair of an evaluation data set, requirement section 4.5.
 *
 * <p>{@code evidences} anchors to {@code doc_id} rather than to a chunk id so the case survives a
 * split strategy change; see {@link io.kbrag.domain.model.EvalEvidence}. {@code messages} is the
 * optional conversation history that turns this into a multi turn case, requirement section 4.5
 * "multi turn case"; a case with turns is measured through the full multi turn rewrite path and is
 * reported in the {@code multi_turn} metrics group rather than the {@code single_turn} one.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_eval_case")
public class EvalCase extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("case_id")
    private String caseId;

    /** Owning data set business id. */
    @TableField("dataset_id")
    private String datasetId;

    /** User query. */
    @TableField("query")
    private String query;

    /** Conversation history JSON array, {@code null} for a single turn case. */
    @TableField("messages")
    private String messages;

    /** Reference answer, optional; consumed only by the LLM-as-judge stage. */
    @TableField("expected_answer")
    private String expectedAnswer;

    /** Whether a correct final answer should refuse because the corpus cannot support an answer. */
    @TableField("expected_refusal")
    private Boolean expectedRefusal;

    /** Anchoring granularity. */
    @TableField("anchor_type")
    private AnchorType anchorType;

    /** JSON array of {@link io.kbrag.domain.model.EvalEvidence}. */
    @TableField("evidences")
    private String evidences;

    /** Lifecycle state. */
    @TableField("status")
    private CaseStatus status;

    /** Origin of the case. */
    @TableField("source")
    private CaseSource source;

    /** Free text operator note. */
    @TableField("note")
    private String note;
}
