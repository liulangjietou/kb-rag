package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.mapper.MailOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * outbox 投递结果的短事务 CAS 边界。
 *
 * <p>只有仍持有对应 lease 版本的实例可以完成 SENT/FAILED；lease 过期后被其他实例重新取得
 * 时，迟到的 SMTP 结果只能得到 0 行并 fail-closed，不会覆盖新状态。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailOutboxCompletionService {

    private final MailOutboxMapper mailOutboxMapper;

    /** 以 lease 版本标记发送成功。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSent(String outboxId, LocalDateTime sentAt, int leaseVersion) {
        if (mailOutboxMapper.markSent(outboxId, sentAt, leaseVersion) != 1) {
            log.error("mail outbox success state lost, errorCode={}, outboxId={}",
                    ErrorCode.INTERNAL_ERROR, outboxId);
            throw new IllegalStateException("mail outbox lease changed before success completion");
        }
    }

    /** 以 lease 版本记录一次失败与下一次重试时间。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(String outboxId, int retryCount, LocalDateTime nextRetryAt,
                           String lastError, int leaseVersion) {
        if (mailOutboxMapper.markFailed(outboxId, retryCount, nextRetryAt,
                lastError, leaseVersion) != 1) {
            log.error("mail outbox failure state lost, errorCode={}, outboxId={}",
                    ErrorCode.INTERNAL_ERROR, outboxId);
            throw new IllegalStateException("mail outbox lease changed before failure completion");
        }
    }
}
