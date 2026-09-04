package io.kbrag.domain.enums;

/**
 * 邮箱验证码生命周期。
 *
 * @author owlzhangfq@gmail.com
 */
public enum EmailVerificationStatus {

    /** 验证码已签发，等待校验。 */
    ISSUED,

    /** 验证码校验通过，短期注册票据可用。 */
    VERIFIED,

    /** 注册票据已被一次性消费。 */
    CONSUMED,

    /** 旧验证码或票据已失效。 */
    INVALIDATED
}
