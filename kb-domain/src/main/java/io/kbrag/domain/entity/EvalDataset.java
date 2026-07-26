package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Evaluation data set: a named collection of query and evidence pairs measured against a knowledge
 * base, requirement section 4.5.
 *
 * <p>{@code datasetRevision} increments on every case insert, edit, delete and status change (see
 * {@code EvalCaseService}); an evaluation run snapshots the revision it saw, which is what lets the
 * compare endpoint refuse to place two runs side by side once the underlying case set moved between
 * them, requirement section 4.6 "corpus change must be flagged".
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_eval_dataset")
public class EvalDataset extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("dataset_id")
    private String datasetId;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Display name. */
    @TableField("name")
    private String name;

    /** Free text description. */
    @TableField("description")
    private String description;

    /** Increments on every case insert, edit, delete and status change. */
    @TableField("dataset_revision")
    private Integer datasetRevision;

    /** Derived redundant count of non deprecated cases, kept in step with every case mutation. */
    @TableField("case_count")
    private Integer caseCount;
}
