package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.RunStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One evaluation run: one retrieval configuration measured against one data set revision at one
 * corpus state, requirement section 4.6.
 *
 * <p>A configuration matrix submission (1 to 6 rows) produces one row per configuration so the
 * report page can lay them out side by side; every row of one submission shares the same
 * {@code datasetRevision} and {@code corpusFingerprint}, which is exactly the pair the compare
 * endpoint checks before allowing two runs to be placed next to each other.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_eval_run")
public class EvalRun extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("run_id")
    private String runId;

    /** Data set this run measures. */
    @TableField("dataset_id")
    private String datasetId;

    /** Knowledge base business id, denormalised for a lookup free listing. */
    @TableField("kb_id")
    private String kbId;

    /** Data set revision snapshotted when the run was created. */
    @TableField("dataset_revision")
    private Integer datasetRevision;

    /** Hash of the active version set plus the embedding version plus the dictionary version. */
    @TableField("corpus_fingerprint")
    private String corpusFingerprint;

    /** JSON {@link io.kbrag.domain.model.EvalRetrievalConfig} used by this run. */
    @TableField("retrieval_config")
    private String retrievalConfig;

    /** Judge model identifier, {@code null} when judging was not requested. */
    @TableField("judge_model")
    private String judgeModel;

    /** Judge prompt version, {@code null} when judging was not requested. */
    @TableField("judge_prompt_version")
    private String judgePromptVersion;

    /** JSON answer-evaluation snapshot; {@code null} keeps this a retrieval-only run. */
    @TableField("answer_eval_config")
    private String answerEvalConfig;

    /** Model used to judge generated answers, {@code null} for a retrieval-only run. */
    @TableField("answer_judge_model")
    private String answerJudgeModel;

    /** Version of the final-answer judging rubric. */
    @TableField("answer_judge_prompt_version")
    private String answerJudgePromptVersion;

    /** Aggregated final-answer metrics, separate from the retrieval metrics document. */
    @TableField("answer_metrics")
    private String answerMetrics;

    /** Lifecycle state. */
    @TableField("status")
    private RunStatus status;

    /** JSON metrics document, grouped by {@code overall/span/document/single_turn/multi_turn}. */
    @TableField("metrics")
    private String metrics;

    /** Cases in the data set at the time this run started. */
    @TableField("case_total")
    private Integer caseTotal;

    /** Cases actually judged, excluding stale and deprecated ones. */
    @TableField("case_effective")
    private Integer caseEffective;

    /** Cases skipped because their evidence was stale at run time. */
    @TableField("case_stale")
    private Integer caseStale;

    /** Cases that carried at least one degradation marker, even after the automatic retry. */
    @TableField("case_degraded")
    private Integer caseDegraded;

    /** Classified failure cause, set only when {@link #status} is {@code FAILED}. */
    @TableField("fail_reason")
    private String failReason;

    /** Execution start timestamp. */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** Execution end timestamp. */
    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
