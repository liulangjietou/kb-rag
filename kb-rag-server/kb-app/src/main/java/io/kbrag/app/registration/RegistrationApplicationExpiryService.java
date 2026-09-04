package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.enums.MailOutboxStatus;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 单个超期待审核申请的独立关闭事务。
 *
 * <p>行锁、PENDING 条件更新、密码摘要清除与自动关闭通知 outbox 同时提交。并发人工审核
 * 获胜时不写通知；单个申请失败也不会回滚其他申请的关闭结果。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationApplicationExpiryService {

    static final String SYSTEM_EXPIRY_REASON = "申请超过审核保留期，已由系统自动关闭";
    private static final String OUTBOX_ID_PREFIX = "mail_";
    private static final int RANDOM_ID_LENGTH = 20;

    private final RegistrationApplicationMapper registrationApplicationMapper;
    private final MailOutboxMapper mailOutboxMapper;

    /**
     * 条件关闭一个申请并写通知 outbox。
     *
     * @return {@code true} 表示本事务完成关闭，{@code false} 表示申请已被并发处理或不再超期
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expireOne(String applicationId, LocalDateTime createdBefore,
                             LocalDateTime reviewedAt) {
        RegistrationApplication application =
                registrationApplicationMapper.selectByApplicationIdForUpdate(applicationId);
        if (application == null
                || application.getStatus() != RegistrationApplicationStatus.PENDING
                || application.getCreatedAt() == null
                || application.getCreatedAt().isAfter(createdBefore)) {
            return false;
        }
        int updated = registrationApplicationMapper.expirePendingIfEligible(
                applicationId, createdBefore, reviewedAt, SYSTEM_EXPIRY_REASON);
        if (updated != 1) {
            return false;
        }
        MailOutbox notification = expiryNotification(application, reviewedAt);
        if (mailOutboxMapper.insert(notification) != 1) {
            log.error("registration expiry outbox insert failed, errorCode={}, outboxId={}",
                    ErrorCode.INTERNAL_ERROR, notification.getOutboxId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册申请自动关闭失败");
        }
        log.info("registration application expired automatically, applicationId={}", applicationId);
        return true;
    }

    private MailOutbox expiryNotification(RegistrationApplication application,
                                          LocalDateTime now) {
        MailOutbox outbox = new MailOutbox();
        outbox.setOutboxId(OUTBOX_ID_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_ID_LENGTH));
        outbox.setRecipient(application.getEmail());
        outbox.setSubject("Knowledge Atlas 注册申请已自动关闭");
        outbox.setBody("您好，" + application.getDisplayName()
                + "：\n您的 Knowledge Atlas 注册申请因超过审核保留期已自动关闭，本结果并非人工驳回。"
                + "\n您可以重新验证邮箱并提交申请。");
        outbox.setStatus(MailOutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(now);
        return outbox;
    }
}
