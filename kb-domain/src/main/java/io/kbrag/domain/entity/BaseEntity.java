package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Columns every business table carries: surrogate key, audit timestamps, optimistic lock and the
 * soft delete flag.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Auto increment surrogate primary key, never exposed through the API. */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** Row creation timestamp. */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** Last update timestamp. */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** Optimistic lock version. */
    @Version
    @TableField(value = "lock_version", fill = FieldFill.INSERT)
    private Integer lockVersion;

    /** Soft delete flag, 0 alive and 1 deleted. */
    @TableLogic
    @TableField(value = "deleted", fill = FieldFill.INSERT)
    private Integer deleted;
}
