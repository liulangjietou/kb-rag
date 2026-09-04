package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 注册审核通过时授予角色的不可变快照。
 *
 * <p>它属于审核事实，不随账号后续调权而变化。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@TableName("t_kb_registration_application_role")
public class RegistrationApplicationRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 注册申请业务标识，和 role_id 共同构成数据库主键。 */
    @TableId(value = "application_id", type = IdType.INPUT)
    private String applicationId;

    /** 审核当时实际授予的角色业务标识。 */
    @TableField("role_id")
    private String roleId;

    /** 审核完成时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
