package io.kbrag.app.registration;

/**
 * 发码短事务的非敏感决策结果。
 *
 * <p>验证码明文由外层调用栈短暂持有，本对象只说明邮件类型、是否需要失败补偿以及真实冷却时间。
 *
 * @author owlzhangfq@gmail.com
 */
public record VerificationIssuanceDecision(boolean occupied,
                                           boolean challengeIssued,
                                           long resendAfterSeconds) {
}
