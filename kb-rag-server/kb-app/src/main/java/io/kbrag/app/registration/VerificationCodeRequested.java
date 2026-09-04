package io.kbrag.app.registration;

/**
 * 公开发码结果；无论邮箱是否已注册都返回同一结构。
 *
 * @param resendAfterSeconds 再次申请前最短等待秒数
 *
 * @author owlzhangfq@gmail.com
 */
public record VerificationCodeRequested(long resendAfterSeconds) {
}
