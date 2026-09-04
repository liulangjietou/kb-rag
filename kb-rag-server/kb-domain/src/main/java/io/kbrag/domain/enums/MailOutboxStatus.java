package io.kbrag.domain.enums;

/**
 * 审核通知邮件发件箱状态。
 *
 * @author owlzhangfq@gmail.com
 */
public enum MailOutboxStatus {

    /** 等待首次发送。 */
    PENDING,

    /** 已成功投递给 SMTP 服务。 */
    SENT,

    /** 最近一次发送失败，等待重试或人工处理。 */
    FAILED
}
