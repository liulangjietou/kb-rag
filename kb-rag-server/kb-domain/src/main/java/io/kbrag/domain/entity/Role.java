package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Role: the single mounting point of permission codes and of the knowledge base data scope.
 *
 * <p>Both are attached here rather than to a user because the operation an operator actually performs
 * is "these ten people do the same job". Attaching a data scope per user would turn that into ten rows
 * that have to be kept in sync by hand, and they would drift.
 *
 * <p>A user holding several roles gets the union of their permissions and the union of their scopes;
 * there is no deny rule. A subtractive rule would make the effective permission set depend on the
 * order the roles are evaluated in, which is not something an operator can reason about from a
 * checkbox grid.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_role")
public class Role extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("role_id")
    private String roleId;

    /** Stable code, referenced by the bootstrap wiring of the built in roles. */
    @TableField("code")
    private String code;

    /** Display name. */
    @TableField("name")
    private String name;

    /** Free text purpose of the role. */
    @TableField("description")
    private String description;

    /** 1 marks a role shipped with the product: its code is fixed and it cannot be deleted. */
    @TableField("builtin")
    private Integer builtin;

    /** 1 grants every knowledge base, which makes the {@code t_kb_role_kb} detail rows irrelevant. */
    @TableField("kb_scope_all")
    private Integer kbScopeAll;

    /**
     * Tells whether the role is shipped with the product.
     *
     * @return {@code true} for a built in role
     */
    public boolean builtin() {
        return builtin != null && builtin == 1;
    }

    /**
     * Tells whether the role sees every knowledge base.
     *
     * @return {@code true} when the scope is unrestricted
     */
    public boolean kbScopeAll() {
        return kbScopeAll != null && kbScopeAll == 1;
    }
}
