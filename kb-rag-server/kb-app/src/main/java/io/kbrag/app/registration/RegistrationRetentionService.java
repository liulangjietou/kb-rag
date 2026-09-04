package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 注册临时状态的有界保留调度器。
 *
 * <p>每次运行按小批次、有限轮数关闭超期待审核申请并物理删除过期验证状态。申请由独立
 * 事务清除密码摘要、保存系统关闭事实并写通知 outbox，单项失败不会拖累同批其他申请。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class RegistrationRetentionService {

    private final EmailVerificationMapper emailVerificationMapper;
    private final RegistrationApplicationMapper registrationApplicationMapper;
    private final RegistrationApplicationExpiryService applicationExpiryService;
    private final RegistrationProperties properties;
    private final Clock clock;

    @Autowired
    public RegistrationRetentionService(EmailVerificationMapper emailVerificationMapper,
                                        RegistrationApplicationMapper registrationApplicationMapper,
                                        RegistrationApplicationExpiryService applicationExpiryService,
                                        RegistrationProperties properties) {
        this(emailVerificationMapper, registrationApplicationMapper, applicationExpiryService, properties,
                Clock.systemDefaultZone());
    }

    RegistrationRetentionService(EmailVerificationMapper emailVerificationMapper,
                                 RegistrationApplicationMapper registrationApplicationMapper,
                                 RegistrationApplicationExpiryService applicationExpiryService,
                                 RegistrationProperties properties, Clock clock) {
        this.emailVerificationMapper = emailVerificationMapper;
        this.registrationApplicationMapper = registrationApplicationMapper;
        this.applicationExpiryService = applicationExpiryService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 按配置清理超期状态；每个 Mapper 调用独立提交一个小批次。 */
    @Scheduled(cron = "${registration.cleanup.cron:0 15 * * * *}")
    public void cleanupExpiredState() {
        RegistrationProperties.Cleanup cleanup = properties.getCleanup();
        if (!cleanup.isEnabled()) {
            return;
        }
        if (!valid(cleanup)) {
            log.error("registration cleanup configuration invalid, errorCode={}",
                    ErrorCode.INTERNAL_ERROR);
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int expiredApplications = expirePendingApplications(cleanup, now);
        int deletedVerifications = deleteExpiredVerifications(cleanup, now);
        if (expiredApplications > 0 || deletedVerifications > 0) {
            log.info("registration retention completed, expiredApplications={}, deletedVerifications={}",
                    expiredApplications, deletedVerifications);
        }
    }

    private int expirePendingApplications(RegistrationProperties.Cleanup cleanup,
                                          LocalDateTime now) {
        LocalDateTime createdBefore = now.minusDays(cleanup.getPendingApplicationTtlDays());
        int total = 0;
        long afterId = 0L;
        for (int batch = 0; batch < cleanup.getMaxBatchesPerRun(); batch++) {
            java.util.List<RegistrationApplication> candidates =
                    registrationApplicationMapper.selectExpiredPendingBatch(
                            createdBefore, afterId, cleanup.getBatchSize());
            if (candidates.isEmpty()) {
                break;
            }
            for (RegistrationApplication candidate : candidates) {
                afterId = Math.max(afterId, candidate.getId());
                try {
                    if (applicationExpiryService.expireOne(
                            candidate.getApplicationId(), createdBefore, now)) {
                        total++;
                    }
                } catch (RuntimeException exception) {
                    log.error("registration application expiry failed, errorCode={}, applicationId={}, cause={}",
                            ErrorCode.INTERNAL_ERROR, candidate.getApplicationId(),
                            exception.getClass().getSimpleName());
                }
            }
            if (candidates.size() < cleanup.getBatchSize()) {
                break;
            }
        }
        return total;
    }

    private int deleteExpiredVerifications(RegistrationProperties.Cleanup cleanup,
                                           LocalDateTime now) {
        LocalDateTime activeExpiredBefore =
                now.minusHours(cleanup.getVerificationRetentionHours());
        LocalDateTime terminalBefore =
                now.minusDays(cleanup.getTerminalVerificationRetentionDays());
        return drainBatches(cleanup.getBatchSize(), () ->
                emailVerificationMapper.deleteExpiredBatch(activeExpiredBefore,
                        activeExpiredBefore, now, terminalBefore, cleanup.getBatchSize()),
                cleanup.getMaxBatchesPerRun());
    }

    private int drainBatches(int batchSize, BatchOperation operation, int maxBatches) {
        int total = 0;
        for (int index = 0; index < maxBatches; index++) {
            int affected = operation.execute();
            total += affected;
            if (affected < batchSize) {
                break;
            }
        }
        return total;
    }

    private boolean valid(RegistrationProperties.Cleanup cleanup) {
        return cleanup.getVerificationRetentionHours() > 0
                && cleanup.getTerminalVerificationRetentionDays() > 0
                && cleanup.getPendingApplicationTtlDays() > 0
                && cleanup.getBatchSize() > 0
                && cleanup.getMaxBatchesPerRun() > 0;
    }

    /**
     * 单批状态变更操作。
     *
     * @author owlzhangfq@gmail.com
     */
    @FunctionalInterface
    private interface BatchOperation {

        int execute();
    }
}
