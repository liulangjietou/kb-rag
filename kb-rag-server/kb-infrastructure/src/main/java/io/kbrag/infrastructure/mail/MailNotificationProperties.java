package io.kbrag.infrastructure.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMTP 通知邮件配置。
 *
 * <p>用户名和授权码只从环境变量绑定，应用不会记录完整配置对象。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mail")
public class MailNotificationProperties {

    /** SMTP 和套接字超时的有效上界。 */
    private static final int MAX_TIMEOUT_MS = 60_000;

    private boolean enabled;
    private String host = "smtp.163.com";
    private int port = 465;
    private String username = "";
    private String password = "";
    private String from = "";
    private String loginUrl = "";
    private boolean sslEnabled = true;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;
    private int writeTimeoutMs = 10000;

    /**
     * 解析最终发件地址；未单独配置时使用 SMTP 登录账号。
     *
     * @return 非空发件地址，或空字符串
     */
    public String effectiveFrom() {
        if (hasText(from)) {
            return from.trim();
        }
        return hasText(username) ? username.trim() : "";
    }

    /**
     * 解析邮件中展示的登录地址。
     *
     * @return 配置地址；空配置时不在通知中展示登录链接
     */
    public String effectiveLoginUrl() {
        return hasText(loginUrl) ? loginUrl.trim() : "";
    }

    /**
     * 判断发送所需开关和凭据是否完整。
     *
     * @return 配置可发送时返回 {@code true}
     */
    public boolean ready() {
        return enabled && hasText(host) && port > 0 && port <= 65_535
                && hasText(username) && hasText(password) && hasText(effectiveFrom())
                && validTimeout(connectTimeoutMs) && validTimeout(readTimeoutMs) && validTimeout(writeTimeoutMs);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean validTimeout(int value) {
        return value > 0 && value <= MAX_TIMEOUT_MS;
    }
}
