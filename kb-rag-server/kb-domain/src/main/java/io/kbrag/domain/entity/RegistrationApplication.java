package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 完成邮箱验证后提交的注册申请。
 *
 * <p>审核通过前不创建 {@code t_kb_admin_user} 账号，避免待审核申请获得登录会话。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = {"passwordHash", "submissionTicketHash"})
@TableName("t_kb_registration_application")
public class RegistrationApplication extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 对外业务标识。 */
    @TableField("application_id")
    private String applicationId;

    /** 已验证并标准化的登录邮箱。 */
    @TableField("email")
    private String email;

    /** 浏览器为一次提交生成的幂等标识。 */
    @TableField("submission_id")
    private String submissionId;

    /** 与幂等标识绑定的高熵注册票据 SHA-256，仅用于确认重试属于同一提交。 */
    @TableField("submission_ticket_hash")
    private String submissionTicketHash;

    /** 申请人展示名。 */
    @TableField("display_name")
    private String displayName;

    /** 申请人所属团队，可为空。 */
    @TableField(value = "team_name", updateStrategy = FieldStrategy.ALWAYS)
    private String teamName;

    /** BCrypt 密码摘要，接口和日志不得回传。 */
    @TableField("password_hash")
    private String passwordHash;

    /** 申请人填写的用途说明。 */
    @TableField(value = "application_note", updateStrategy = FieldStrategy.ALWAYS)
    private String applicationNote;

    /** 当前审核状态。 */
    @TableField("status")
    private RegistrationApplicationStatus status;

    /** 本申请所消费邮箱票据的验证通过时间。 */
    @TableField("email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** 审核人用户业务标识。 */
    @TableField(value = "reviewed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String reviewedBy;

    /** 审核完成时间。 */
    @TableField(value = "reviewed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime reviewedAt;

    /** 拒绝原因或审核备注。 */
    @TableField(value = "review_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String reviewReason;

    /** 审核通过时分配的租户业务标识。 */
    @TableField(value = "approved_tenant_id", updateStrategy = FieldStrategy.ALWAYS)
    private String approvedTenantId;

    /** 审核通过后创建的账号业务标识。 */
    @TableField(value = "approved_user_id", updateStrategy = FieldStrategy.ALWAYS)
    private String approvedUserId;
}
