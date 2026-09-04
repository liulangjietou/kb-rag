package io.kbrag.app.registration;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 事务外 BCrypt 的进程级非等待 CPU 舱壁。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class RegistrationPasswordHasher {

    private static final int MAX_SAFE_CONCURRENCY = 4;

    private final BCryptPasswordEncoder passwordEncoder;
    private final Semaphore permits;

    public RegistrationPasswordHasher(BCryptPasswordEncoder passwordEncoder,
                                      RegistrationProperties properties) {
        int concurrency = properties.getPasswordHashConcurrency();
        if (concurrency <= 0 || concurrency > MAX_SAFE_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "registration password hash concurrency must be between 1 and "
                            + MAX_SAFE_CONCURRENCY);
        }
        this.passwordEncoder = passwordEncoder;
        this.permits = new Semaphore(concurrency, true);
    }

    /**
     * 哈希槽已满时立即拒绝，不让匿名请求排队占满 Web 工作线程。
     *
     * @param password 已通过长度和复杂度校验的密码
     * @return BCrypt 摘要
     */
    public String hash(String password) {
        if (!permits.tryAcquire()) {
            log.info("registration password hash bulkhead rejected request, errorCode={}",
                    ErrorCode.RATE_LIMITED);
            throw new BizException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
        }
        try {
            return passwordEncoder.encode(password);
        } finally {
            permits.release();
        }
    }
}
