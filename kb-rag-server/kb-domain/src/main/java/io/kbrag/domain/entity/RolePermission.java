package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Binding of one permission code to one role.
 *
 * <p>Rebinding a role replaces the whole set rather than diffing it: the console always submits the
 * complete checkbox state, and a replace cannot leave a stale grant behind the way a partial diff can.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_role_permission")
public class RolePermission extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Role business id. */
    @TableField("role_id")
    private String roleId;

    /** Permission code granted to that role. */
    @TableField("permission_code")
    private String permissionCode;
}
