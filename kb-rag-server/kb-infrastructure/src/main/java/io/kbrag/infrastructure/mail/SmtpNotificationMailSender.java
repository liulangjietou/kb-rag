package io.kbrag.infrastructure.mail;

import io.kbrag.domain.port.NotificationMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 基于 Spring JavaMail 的纯文本通知实现。
 *
 * @author owlzhangfq@gmail.com
 */
public class SmtpNotificationMailSender implements NotificationMailSender {

    private final MailNotificationProperties properties;
    private final JavaMailSender mailSender;

    /**
     * 创建 SMTP 发送器。
     *
     * @param properties 邮件配置
     * @param mailSender JavaMail 客户端，配置不完整时为 {@code null}
     */
    public SmtpNotificationMailSender(MailNotificationProperties properties, JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    @Override
    public boolean available() {
        return mailSender != null && properties.ready();
    }

    @Override
    public String loginUrl() {
        return properties.effectiveLoginUrl();
    }

    @Override
    public void send(String recipient, String subject, String body) {
        if (!available()) {
            throw new IllegalStateException("mail notification is not configured");
        }
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("mail recipient must not be blank");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.effectiveFrom());
        message.setTo(recipient.trim());
        message.setSubject(subject == null ? "" : subject);
        message.setText(body == null ? "" : body);
        mailSender.send(message);
    }
}
