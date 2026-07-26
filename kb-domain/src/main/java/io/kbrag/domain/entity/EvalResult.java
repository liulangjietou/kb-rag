package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Per case judgment of one evaluation run, the drill down detail behind the aggregated metrics.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_eval_result")
public class EvalResult extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("result_id")
    private String resultId;

    /** Owning run business id. */
    @TableField("run_id")
    private String runId;

    /** Case business id judged. */
    @TableField("case_id")
    private String caseId;

    /** 1 when at least one evidence (or the anchored document) was recalled in the top K, else 0. */
    @TableField("hit")
    private Integer hit;

    /** One based rank of the first hit, {@code null} when the case was not hit. */
    @TableField("hit_rank")
    private Integer hitRank;

    /** JSON array: best aggregate coverage ratio achieved per evidence. */
    @TableField("overlap_ratios")
    private String overlapRatios;

    /**
     * Evidences covered within the top K.
     *
     * <p>Persisted since M4c so the release gate can recompute {@code Recall@K} on the intersection of the
     * effective cases of two runs. Deriving it back from {@link #overlapRatios} would compare a per evidence
     * best ratio against the run's aggregate coverage decision and disagree with the run's own metrics.
     */
    @TableField("evidence_hit_count")
    private Integer evidenceHitCount;

    /** Evidences the case declares, the denominator of the per case {@code Recall@K}. */
    @TableField("evidence_total_count")
    private Integer evidenceTotalCount;

    /** JSON array of the chunk ids the top K returned for this case. */
    @TableField("recalled_chunk_ids")
    private String recalledChunkIds;

    /** JSON array of degradation markers observed while judging this case. */
    @TableField("degraded")
    private String degraded;

    /** Automatic retries this case went through because of a degradation. */
    @TableField("retry_count")
    private Integer retryCount;

    /** LLM-as-judge score, {@code null} when judging was not requested or the case has no expected answer. */
    @TableField("judge_score")
    private Integer judgeScore;

    /** LLM-as-judge free text rationale. */
    @TableField("judge_reason")
    private String judgeReason;
}
