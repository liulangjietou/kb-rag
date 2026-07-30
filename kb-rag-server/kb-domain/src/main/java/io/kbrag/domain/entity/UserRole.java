package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.RoleGrantSource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Binding of one role to one user.
 *
 * <p>A user with no row here can log in and gets nothing: authentication and authorisation are
 * separate decisions, and a directory account that just appeared has to be authenticated before an
 * operator can even see it in order to grant it anything.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_user_role")
public class UserRole extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** User business id. */
    @TableField("user_id")
    private String userId;

    /** Role business id. */
    @TableField("role_id")
    private String roleId;

    /** Who granted the binding; synchronisation only ever replaces its own rows. */
    @TableField("granted_by")
    private RoleGrantSource grantedBy;
}
