package io.kbrag.app.registration;

import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.mapper.MailOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * outbox 单任务有界 lease 的短事务边界。
 *
 * <p>行锁只覆盖 ready 任务选择与 lease CAS；返回前事务已提交，SMTP 不会占用该事务的
 * 数据库连接。lease 使用现有 next_retry_at 与 lock_version，无需新增持久化字段。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class MailOutboxLeaseService {

    private static final int SINGLE_TASK = 1;
    private static final int MAX_LEASE_SECONDS = 300;

    private final MailOutboxMapper mailOutboxMapper;
    private final RegistrationProperties properties;
    private final int leaseSeconds;

    public MailOutboxLeaseService(MailOutboxMapper mailOutboxMapper,
                                  RegistrationProperties properties) {
        this.mailOutboxMapper = mailOutboxMapper;
        this.properties = properties;
        int configuredLeaseSeconds = properties.getOutbox().getLeaseSeconds();
        if (configuredLeaseSeconds <= 0 || configuredLeaseSeconds > MAX_LEASE_SECONDS) {
            throw new IllegalArgumentException(
                    "registration outbox lease seconds must be between 1 and " + MAX_LEASE_SECONDS);
        }
        this.leaseSeconds = configuredLeaseSeconds;
    }

    /**
     * 取得一项到期任务并提交 lease。
     *
     * @param readyAt 当前调度时刻
     * @return 已携带 lease 版本的任务；空表示当前无任务或 CAS 已被其他实例推进
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<MailOutbox> claimNext(LocalDateTime readyAt) {
        int maxRetries = Math.max(1, properties.getOutbox().getMaxRetries());
        List<MailOutbox> ready = mailOutboxMapper.selectReadyBatch(
                readyAt, SINGLE_TASK, maxRetries);
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        MailOutbox task = ready.get(0);
        int expectedVersion = task.getLockVersion() == null ? 0 : task.getLockVersion();
        LocalDateTime leaseUntil = readyAt.plusSeconds(leaseSeconds);
        int claimed = mailOutboxMapper.claimDeliveryLease(task.getOutboxId(), readyAt,
                leaseUntil, maxRetries, expectedVersion);
        if (claimed != 1) {
            return Optional.empty();
        }
        task.setNextRetryAt(leaseUntil);
        task.setLockVersion(expectedVersion + 1);
        return Optional.of(task);
    }
}
