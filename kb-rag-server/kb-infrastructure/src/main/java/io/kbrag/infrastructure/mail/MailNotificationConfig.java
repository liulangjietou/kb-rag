package io.kbrag.infrastructure.mail;

import io.kbrag.domain.port.NotificationMailSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SMTP 适配器装配点。
 *
 * <p>配置不完整时不创建 JavaMail 客户端，避免每次通知都尝试连接一个不存在的 SMTP 服务。
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(MailNotificationProperties.class)
public class MailNotificationConfig {

    /**
     * 创建始终存在的通知端口；未配置环境只会报告不可用。
     *
     * @param properties 邮件配置
     * @return SMTP 通知端口
     */
    @Bean
    public NotificationMailSender notificationMailSender(MailNotificationProperties properties) {
        return new SmtpNotificationMailSender(properties, buildMailSender(properties));
    }

    /**
     * 仅在配置完整时构造 JavaMail 客户端。
     *
     * @param properties 邮件配置
     * @return JavaMail 客户端，配置不完整时返回 {@code null}
     */
    static JavaMailSenderImpl buildMailSender(MailNotificationProperties properties) {
        if (!properties.ready()) {
            return null;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost().trim());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername().trim());
        sender.setPassword(properties.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties javaMail = sender.getJavaMailProperties();
        javaMail.setProperty("mail.transport.protocol", "smtp");
        javaMail.setProperty("mail.smtp.auth", "true");
        javaMail.setProperty("mail.smtp.ssl.enable", Boolean.toString(properties.isSslEnabled()));
        // 非隐式 TLS 模式只允许强制 STARTTLS；服务器不支持升级时必须在发送凭据前失败。
        javaMail.setProperty("mail.smtp.starttls.enable", Boolean.toString(!properties.isSslEnabled()));
        javaMail.setProperty("mail.smtp.starttls.required", Boolean.toString(!properties.isSslEnabled()));
        javaMail.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        javaMail.setProperty("mail.smtp.connectiontimeout", Integer.toString(properties.getConnectTimeoutMs()));
        javaMail.setProperty("mail.smtp.timeout", Integer.toString(properties.getReadTimeoutMs()));
        javaMail.setProperty("mail.smtp.writetimeout", Integer.toString(properties.getWriteTimeoutMs()));
        return sender;
    }
}
