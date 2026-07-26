package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Asynchronous task record, drives the console task monitor and the compensation scan.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_task")
public class KbTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("task_id")
    private String taskId;

    /** Task category. */
    @TableField("task_type")
    private TaskType taskType;

    /** Business id the task operates on, for example a document version id. */
    @TableField("biz_id")
    private String bizId;

    /** Lifecycle state. */
    @TableField("status")
    private TaskStatus status;

    /** Number of retries already performed. */
    @TableField("retry_count")
    private Integer retryCount;

    /** Classified failure cause. */
    @TableField("fail_reason")
    private String failReason;

    /** Completion percentage between 0 and 100. */
    @TableField("progress")
    private Integer progress;

    /**
     * Units the task deliberately skipped, {@code null} for the task types that never skip anything.
     *
     * <p>Written by the graph extraction, requirement section 4.9: a chunk whose model answer fails the
     * output validation is dropped and counted rather than failing the run, so the count is the only place
     * a partially useful extraction differs from a complete one - and it has to survive a success.
     */
    @TableField("skipped_count")
    private Integer skippedCount;
}
