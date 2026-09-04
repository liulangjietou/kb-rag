package io.kbrag.app.registration;

import io.kbrag.domain.mapper.EmailVerificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 验证码 SMTP 交付后的短事务状态推进。
 *
 * <p>SMTP 在事务外执行。本服务以 verificationId 与 code HMAC 双重 CAS，把本次 challenge
 * 精确推进为已交付或撤销；迟到结果不能覆盖并行轮换后的新验证码。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationDeliveryService {

    private final EmailVerificationMapper emailVerificationMapper;

    /** SMTP 成功后确认本次 challenge 已交付。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int complete(String verificationId, String codeHmac, LocalDateTime deliveredAt) {
        return emailVerificationMapper.markChallengeDelivered(
                verificationId, codeHmac, deliveredAt);
    }

    /** SMTP 失败后撤销本次未交付 challenge；有效旧 ticket 由 SQL 条件保留。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int cancel(String verificationId, String codeHmac, LocalDateTime cancelledAt) {
        return emailVerificationMapper.cancelIssuedChallenge(
                verificationId, codeHmac, cancelledAt);
    }
}
