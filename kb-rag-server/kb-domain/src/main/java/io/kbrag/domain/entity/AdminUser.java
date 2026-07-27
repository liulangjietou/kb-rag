package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Console administrator account.
 *
 * <p>The M1 deployment is single administrator, the table still supports multiple rows so the
 * account model does not have to be reshaped later.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "passwordHash")
@TableName("t_kb_admin_user")
public class AdminUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Login name, unique. */
    @TableField("username")
    private String username;

    /** BCrypt hash, never logged and never returned by the API. */
    @TableField("password_hash")
    private String passwordHash;

    /** 1 forces a password change right after login. */
    @TableField("must_change_password")
    private Integer mustChangePassword;

    /** Timestamp of the last successful login. */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * Tells whether the account still has to rotate its bootstrap password.
     *
     * @return {@code true} when a password change is mandatory
     */
    public boolean mustChangePassword() {
        return mustChangePassword != null && mustChangePassword == 1;
    }
}
