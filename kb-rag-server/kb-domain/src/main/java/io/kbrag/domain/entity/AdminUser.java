package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Console user account, both locally created accounts and directory accounts provisioned by single
 * sign-on.
 *
 * <p>This used to be the single administrator row of M1. The RBAC milestone grew it into the user
 * table rather than adding a second one: the session token table and the login audit table both key
 * on {@code username}, so a separate table would have meant reshaping those two and maintaining one
 * person in two places.
 *
 * <p>Permissions live nowhere on this row. They are derived from the roles bound in
 * {@code t_kb_user_role}, so revoking a role revokes it everywhere at once.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "passwordHash")
@TableName("t_kb_admin_user")
public class AdminUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API and referenced by the role bindings. */
    @TableField("user_id")
    private String userId;

    /** Login name, unique. For a directory account this is the domain login name without the suffix. */
    @TableField("username")
    private String username;

    /** Display name shown in the console header and in user lists. */
    @TableField("display_name")
    private String displayName;

    /** Contact address, display only. */
    @TableField("email")
    private String email;

    /** Where the account came from, which decides how a login verifies its password. */
    @TableField("source")
    private UserSource source;

    /** Lifecycle state; a disabled account is refused at login. */
    @TableField("status")
    private UserStatus status;

    /** BCrypt hash, never logged and never returned by the API; {@code null} for a directory account. */
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

    /**
     * Tells whether the account may log in at all.
     *
     * @return {@code true} unless an operator suspended it
     */
    public boolean enabled() {
        return status == null || status == UserStatus.ENABLED;
    }

    /**
     * Tells whether the password of this account is verified by the directory rather than locally.
     *
     * @return {@code true} for a single sign-on account
     */
    public boolean directoryAccount() {
        return source == UserSource.LDAP;
    }
}
