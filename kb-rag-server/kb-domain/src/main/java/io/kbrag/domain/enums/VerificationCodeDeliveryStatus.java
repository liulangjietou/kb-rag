package io.kbrag.domain.enums;

/**
 * 注册验证码的邮件交付状态。
 *
 * <p>验证码生命周期与注册票据生命周期正交：已有有效票据时仍可签发新的验证码，因此不能
 * 复用 {@link EmailVerificationStatus} 表达 SMTP 是否已经完成。
 *
 * @author owlzhangfq@gmail.com
 */
public enum VerificationCodeDeliveryStatus {

    /** 验证码状态已落库，SMTP 尚未确认完成。 */
    ISSUING,

    /** SMTP 调用已成功，验证码才允许被校验或复用。 */
    DELIVERED,

    /** 当前没有可校验的验证码。 */
    NONE
}
