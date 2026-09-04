package io.kbrag.infrastructure.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SMTP 就绪判定、SSL 默认值与禁用态不连接契约。
 *
 * @author owlzhangfq@gmail.com
 */
class MailNotificationConfigTest {

    @Test
    void incompleteConfigurationMustNotCreateClientOrAttemptSend() {
        MailNotificationProperties properties = completeProperties();
        properties.setHost(" ");

        assertThat(MailNotificationConfig.buildMailSender(properties)).isNull();

        JavaMailSender delegate = mock(JavaMailSender.class);
        SmtpNotificationMailSender sender = new SmtpNotificationMailSender(properties, delegate);
        assertThat(sender.available()).isFalse();
        assertThatThrownBy(() -> sender.send("person@example.com", "subject", "body"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mail notification is not configured");
        verifyNoInteractions(delegate);
    }

    @Test
    void port465UsesSslAndBoundedSocketTimeouts() {
        MailNotificationProperties properties = completeProperties();
        JavaMailSenderImpl sender = MailNotificationConfig.buildMailSender(properties);

        assertThat(sender).isNotNull();
        assertThat(sender.getHost()).isEqualTo("smtp.example.com");
        assertThat(sender.getPort()).isEqualTo(465);
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.starttls.enable", "false")
                .containsEntry("mail.smtp.starttls.required", "false")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.connectiontimeout", "3210")
                .containsEntry("mail.smtp.timeout", "6540")
                .containsEntry("mail.smtp.writetimeout", "9870");
    }

    @Test
    void nonImplicitSslRequiresStartTlsBeforeAuthentication() {
        MailNotificationProperties properties = completeProperties();
        properties.setPort(587);
        properties.setSslEnabled(false);

        JavaMailSenderImpl sender = MailNotificationConfig.buildMailSender(properties);

        assertThat(sender).isNotNull();
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.ssl.enable", "false")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void invalidPortOrTimeoutMustFailClosed() {
        MailNotificationProperties properties = completeProperties();
        properties.setPort(65_536);
        assertThat(properties.ready()).isFalse();
        assertThat(MailNotificationConfig.buildMailSender(properties)).isNull();

        properties = completeProperties();
        properties.setReadTimeoutMs(0);
        assertThat(properties.ready()).isFalse();

        properties = completeProperties();
        properties.setWriteTimeoutMs(60_001);
        assertThat(properties.ready()).isFalse();
    }

    @Test
    void blankFromFallsBackToUsernameAndBlankLoginUrlOmitsConsoleLink() {
        MailNotificationProperties properties = completeProperties();
        properties.setFrom("");
        properties.setLoginUrl(" ");
        JavaMailSender delegate = mock(JavaMailSender.class);
        SmtpNotificationMailSender sender = new SmtpNotificationMailSender(properties, delegate);

        assertThat(sender.available()).isTrue();
        assertThat(sender.loginUrl()).isEmpty();
        sender.send(" person@example.com ", "审核通过", "可以登录");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(delegate).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("mail-user@example.com");
        assertThat(message.getValue().getTo()).containsExactly("person@example.com");
        assertThat(message.getValue().getSubject()).isEqualTo("审核通过");
        assertThat(message.getValue().getText()).isEqualTo("可以登录");
    }

    private MailNotificationProperties completeProperties() {
        MailNotificationProperties properties = new MailNotificationProperties();
        properties.setEnabled(true);
        properties.setHost("smtp.example.com");
        properties.setPort(465);
        properties.setUsername("mail-user@example.com");
        properties.setPassword("test-authorization-code");
        properties.setFrom("");
        properties.setSslEnabled(true);
        properties.setConnectTimeoutMs(3210);
        properties.setReadTimeoutMs(6540);
        properties.setWriteTimeoutMs(9870);
        return properties;
    }
}
