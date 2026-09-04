package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 匿名注册同步邮件的进程级并发舱壁。
 *
 * <p>许可必须在事务代理外获取，并覆盖短事务、同步 SMTP 与失败补偿。默认容量 4，小于默认
 * Hikari 连接池 10，慢 SMTP 请求因而不能占满全部数据库连接。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class RegistrationMailBulkhead {

    private static final int MAX_SAFE_CONCURRENCY = 8;

    private final Semaphore permits;

    public RegistrationMailBulkhead(RegistrationProperties properties) {
        int concurrency = properties.getMailConcurrency();
        if (concurrency <= 0 || concurrency > MAX_SAFE_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "registration mail concurrency must be between 1 and " + MAX_SAFE_CONCURRENCY);
        }
        this.permits = new Semaphore(concurrency, true);
    }

    /**
     * 不等待地执行一次同步邮件流程；舱壁已满时在进入数据库事务前拒绝。
     */
    public <T> T execute(Supplier<T> action) {
        if (!permits.tryAcquire()) {
            log.info("registration mail bulkhead rejected request, errorCode={}, layer=mail-concurrency",
                    ErrorCode.RATE_LIMITED);
            throw new BizException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
