package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One entry of the permission code catalogue.
 *
 * <p>The rows are seeded by the migration and never written by the API: a permission code only means
 * something if some endpoint declares it, so inventing one at runtime would produce a checkbox that
 * grants nothing. The table exists because the console has to render the grid grouped by module with
 * Chinese labels, and that grouping is data, not code.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_permission")
public class Permission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Permission code, {@code module:action}, mirrored by a constant in the domain layer. */
    @TableField("code")
    private String code;

    /** Display label of the permission. */
    @TableField("name")
    private String name;

    /** Grouping key of the console grid. */
    @TableField("module")
    private String module;

    /** Display label of the group. */
    @TableField("module_name")
    private String moduleName;

    /** Display order inside the group. */
    @TableField("sort_order")
    private Integer sortOrder;
}
