package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.port.NotificationMailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 单封 outbox 邮件的无事务 SMTP 编排器。
 *
 * <p>短事务先建立有界 lease 并提交，随后在事务外同步 SMTP，最后用另一个短事务 CAS
 * 完成 SENT/FAILED。SMTP 接受邮件后进程崩溃仍可能产生 at-least-once 重复，这是没有
 * 分布式 SMTP 事务时可接受且可观测的边界。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class MailOutboxDeliveryService {

    private static final int MAX_BACKOFF_EXPONENT = 10;

    private final MailOutboxLeaseService leaseService;
    private final MailOutboxCompletionService completionService;
    private final NotificationMailSender mailSender;
    private final RegistrationProperties properties;
    private final Clock clock;

    @Autowired
    public MailOutboxDeliveryService(MailOutboxLeaseService leaseService,
                                     MailOutboxCompletionService completionService,
                                     NotificationMailSender mailSender,
                                     RegistrationProperties properties) {
        this(leaseService, completionService, mailSender, properties, Clock.systemDefaultZone());
    }

    MailOutboxDeliveryService(MailOutboxLeaseService leaseService,
                              MailOutboxCompletionService completionService,
                              NotificationMailSender mailSender,
                              RegistrationProperties properties,
                              Clock clock) {
        this.leaseService = leaseService;
        this.completionService = completionService;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 交付一个到期任务。
     *
     * @return {@code false} 表示当前无可交付任务，调度循环可以结束
     */
    public boolean deliverOne() {
        RegistrationProperties.Outbox config = properties.getOutbox();
        Optional<MailOutbox> claimed = leaseService.claimNext(LocalDateTime.now(clock));
        if (claimed.isEmpty()) {
            return false;
        }
        MailOutbox task = claimed.get();
        int leaseVersion = task.getLockVersion() == null ? 0 : task.getLockVersion();
        try {
            mailSender.send(task.getRecipient(), task.getSubject(), task.getBody());
        } catch (RuntimeException exception) {
            recordFailure(task, config, leaseVersion, exception);
            return true;
        }
        completionService.markSent(task.getOutboxId(), LocalDateTime.now(clock), leaseVersion);
        log.info("mail outbox sent, outboxId={}", task.getOutboxId());
        return true;
    }

    private void recordFailure(MailOutbox task, RegistrationProperties.Outbox config,
                               int leaseVersion, RuntimeException exception) {
        int retries = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        LocalDateTime failedAt = LocalDateTime.now(clock);
        LocalDateTime nextRetryAt = retries >= Math.max(1, config.getMaxRetries())
                ? null : failedAt.plusSeconds(backoffSeconds(config, retries));
        completionService.markFailed(task.getOutboxId(), retries, nextRetryAt,
                "mail transport failed", leaseVersion);
        log.error("mail outbox send failed, errorCode={}, outboxId={}, retryCount={}, cause={}",
                ErrorCode.INTERNAL_ERROR, task.getOutboxId(), retries,
                exception.getClass().getSimpleName());
    }

    private long backoffSeconds(RegistrationProperties.Outbox config, int retries) {
        long base = Math.max(1, config.getRetryDelaySeconds());
        int exponent = Math.min(MAX_BACKOFF_EXPONENT, Math.max(0, retries - 1));
        return base * (1L << exponent);
    }
}
