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

    /** Final answer produced through the same prompt/model path as the served chat endpoint. */
    @TableField("generated_answer")
    private String generatedAnswer;

    /** Whether this case required generation and final-answer judgment. */
    @TableField("answer_judge_requested")
    private Boolean answerJudgeRequested;

    /** Wall time of the generation call in milliseconds. */
    @TableField("generation_latency_ms")
    private Integer generationLatencyMs;

    /** Rounded mean of the five final-answer judge dimensions. */
    @TableField("answer_score")
    private Integer answerScore;

    /** Correctness of the generated answer against the expected answer. */
    @TableField("answer_correctness")
    private Integer answerCorrectness;

    /** Degree to which claims are supported by the retrieved passages. */
    @TableField("answer_faithfulness")
    private Integer answerFaithfulness;

    /** Coverage of the expected answer. */
    @TableField("answer_completeness")
    private Integer answerCompleteness;

    /** Correctness of the generated answer's numbered citations. */
    @TableField("citation_correctness")
    private Integer citationCorrectness;

    /** Coverage of claims that require a citation. */
    @TableField("citation_completeness")
    private Integer citationCompleteness;

    /** Whether the answer made the expected refuse/answer decision. */
    @TableField("refusal_correct")
    private Boolean refusalCorrect;

    /** Final-answer judge rationale or classified failure explanation. */
    @TableField("answer_judge_reason")
    private String answerJudgeReason;
}
