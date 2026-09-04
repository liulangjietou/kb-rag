package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.EmailVerificationStatus;
import io.kbrag.domain.enums.VerificationCodeDeliveryStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 邮箱注册验证码及一次性票据的服务端状态。
 *
 * <p>只持久化 HMAC/哈希；验证码、注册票据与来源 IP 明文不得进入数据库或日志。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = {"codeHmac", "ticketHash", "requestIpHash"})
@TableName("t_kb_email_verification")
public class EmailVerification extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 对外业务标识。 */
    @TableField("verification_id")
    private String verificationId;

    /** 已统一小写并去除首尾空白的邮箱。 */
    @TableField("email")
    private String email;

    /** 验证码的带密钥 HMAC，绝不保存验证码明文。 */
    @TableField(value = "code_hmac", updateStrategy = FieldStrategy.ALWAYS)
    private String codeHmac;

    /** SMTP 交付状态；只有 DELIVERED 的验证码才允许校验或冷却期复用。 */
    @TableField("code_delivery_status")
    private VerificationCodeDeliveryStatus codeDeliveryStatus;

    /** 当前生命周期状态。 */
    @TableField("status")
    private EmailVerificationStatus status;

    /** 剩余验证码校验次数。 */
    @TableField("attempts_remaining")
    private Integer attemptsRemaining;

    /** 验证码绝对失效时间。 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 同一邮箱允许再次发送的最早时间。 */
    @TableField("resend_available_at")
    private LocalDateTime resendAvailableAt;

    /** 一次性注册票据的 SHA-256 摘要。 */
    @TableField(value = "ticket_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String ticketHash;

    /** 注册票据绝对失效时间。 */
    @TableField(value = "ticket_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime ticketExpiresAt;

    /** 验证码校验通过时间。 */
    @TableField(value = "verified_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime verifiedAt;

    /** 注册票据被消费的时间。 */
    @TableField(value = "consumed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime consumedAt;

    /** 来源 IP 的带密钥哈希，只用于限流审计。 */
    @TableField("request_ip_hash")
    private String requestIpHash;
}
