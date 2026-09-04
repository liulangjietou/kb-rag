package io.kbrag.domain.port;

/**
 * 通知邮件发送端口。
 *
 * @author owlzhangfq@gmail.com
 */
public interface NotificationMailSender {

    /**
     * 判断 SMTP 所需配置是否完整。
     *
     * @return 配置完整且开关开启时返回 {@code true}
     */
    boolean available();

    /**
     * 返回审核结果邮件里的控制台登录地址。
     *
     * @return 登录页地址
     */
    String loginUrl();

    /**
     * 发送纯文本通知邮件。
     *
     * @param recipient 收件邮箱
     * @param subject 邮件主题
     * @param body 邮件正文
     */
    void send(String recipient, String subject, String body);
}
