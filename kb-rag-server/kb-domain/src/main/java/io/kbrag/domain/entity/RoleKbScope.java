package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One knowledge base inside the data scope of one role.
 *
 * <p>Rows are only consulted when the role does not carry {@code kb_scope_all}: a role granted the
 * whole estate must not need a row per base, otherwise creating a knowledge base would silently hide
 * it from the administrators until someone remembered to widen every scope.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_role_kb")
public class RoleKbScope extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Role business id. */
    @TableField("role_id")
    private String roleId;

    /** Knowledge base business id visible to that role. */
    @TableField("kb_id")
    private String kbId;
}
