package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.port.NotificationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审核结果邮件 outbox 派发器。
 *
 * <p>发送失败不会回滚已经完成的审核；任务按指数退避重试，到达上限后保留 FAILED 记录供
 * 运维排查。验证码和票据永远不会进入本派发器。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailOutboxDispatcher {

    private final NotificationMailSender mailSender;
    private final RegistrationProperties properties;
    private final MailOutboxDeliveryService deliveryService;
    private final MailOutboxMapper mailOutboxMapper;
    private final AtomicBoolean unavailableReported = new AtomicBoolean(false);

    /** 定期领取 lease 并发送一批到期任务。 */
    @Scheduled(fixedDelayString = "${registration.outbox.dispatch-interval-ms:5000}")
    public void dispatchReady() {
        if (!mailSender.available()) {
            int maxRetries = Math.max(1, properties.getOutbox().getMaxRetries());
            long backlog = mailOutboxMapper.countDeliverableBacklog(maxRetries);
            if (backlog == 0) {
                return;
            }
            if (unavailableReported.compareAndSet(false, true)) {
                log.error("registration outbox mail sender unavailable, errorCode={}, backlog={}",
                        ErrorCode.INTERNAL_ERROR, backlog);
            }
            return;
        }
        if (unavailableReported.compareAndSet(true, false)) {
            log.info("registration outbox mail sender recovered");
        }
        RegistrationProperties.Outbox config = properties.getOutbox();
        int batchSize = Math.max(1, config.getBatchSize());
        for (int index = 0; index < batchSize; index++) {
            // 每封信的 lease 与结果 CAS 各自进入 REQUIRES_NEW；SMTP 本身不持有事务。
            if (!deliveryService.deliverOne()) {
                break;
            }
        }
    }
}
