package io.kbrag.app.registration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公开邮箱注册的安全与调度参数。
 *
 * <p>验证码 HMAC 密钥默认留空，部署未显式注入时注册入口安全关闭，避免把源码中的默认值
 * 当成生产密钥使用。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "registration")
public class RegistrationProperties {

    private boolean enabled = true;
    private String codeHmacKey;
    private int codeTtlMinutes = 10;
    private int ticketTtlMinutes = 15;
    private int resendSeconds = 60;
    private int maxAttempts = 5;
    private int mailConcurrency = 4;
    private int passwordHashConcurrency = 2;
    private int emailRateLimitPerHour = 6;
    private int ipRateLimitPerHour = 20;
    private int globalRateLimitPerMinute = 100;
    private int verifyIpRateLimitPerHour = 60;
    private int verifyGlobalRateLimitPerMinute = 300;
    private int submitIpRateLimitPerHour = 20;
    private int submitGlobalRateLimitPerMinute = 100;
    private Outbox outbox = new Outbox();
    private Cleanup cleanup = new Cleanup();

    /**
     * 邮件 outbox 的批处理与重试参数。
     *
     * @author owlzhangfq@gmail.com
     */
    @Getter
    @Setter
    public static class Outbox {

        private int batchSize = 50;
        private int maxRetries = 5;
        private int retryDelaySeconds = 60;
        private int leaseSeconds = 60;
        private long dispatchIntervalMs = 5_000L;
    }

    /**
     * 匿名验证状态与长期待审核申请的保留策略。
     *
     * @author owlzhangfq@gmail.com
     */
    @Getter
    @Setter
    public static class Cleanup {

        private boolean enabled = true;
        private int verificationRetentionHours = 24;
        private int terminalVerificationRetentionDays = 7;
        private int pendingApplicationTtlDays = 30;
        private int batchSize = 200;
        private int maxBatchesPerRun = 50;
        private String cron = "0 15 * * * *";
    }
}
