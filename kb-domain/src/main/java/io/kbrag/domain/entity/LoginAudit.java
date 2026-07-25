package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.LoginResult;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Console login audit, also the data source of the brute force lock decision.
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_login_audit")
public class LoginAudit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Submitted user name, recorded even when unknown. */
    @TableField("username")
    private String username;

    /** Source address of the attempt. */
    @TableField("ip")
    private String ip;

    /** 1 for a successful attempt. */
    @TableField("success")
    private Integer success;

    /** Classified reason of the attempt. */
    @TableField("reason")
    private LoginResult reason;
}
