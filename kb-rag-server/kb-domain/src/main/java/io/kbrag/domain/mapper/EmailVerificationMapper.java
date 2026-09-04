package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EmailVerification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 邮箱验证码状态的数据访问。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EmailVerificationMapper extends BaseMapper<EmailVerification> {

    /**
     * 原子初始化邮箱唯一行；多实例同时首次发码时仅一个实例完成插入。
     *
     * <p>冲突时不覆盖已经存在的验证码与冷却时间，调用方随后统一锁行并在锁内决定是否轮换。
     *
     * @param entity 首次签发状态
     * @return 1 表示插入成功，0 表示邮箱或业务标识已经存在
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO t_kb_email_verification "
            + "(verification_id, email, code_hmac, code_delivery_status, status, attempts_remaining, expires_at, "
            + "resend_available_at, ticket_hash, ticket_expires_at, verified_at, consumed_at, "
            + "request_ip_hash, created_at, updated_at, lock_version, deleted) VALUES "
            + "(#{verificationId}, #{email}, #{codeHmac}, #{codeDeliveryStatus}, #{status}, "
            + "#{attemptsRemaining}, #{expiresAt}, "
            + "#{resendAvailableAt}, #{ticketHash}, #{ticketExpiresAt}, #{verifiedAt}, #{consumedAt}, "
            + "#{requestIpHash}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(EmailVerification entity);

    /**
     * 锁定单个邮箱的验证码行，串行化发送、校验与重发。
     *
     * @param email 标准化邮箱
     * @return 验证状态，不存在时返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_email_verification "
            + "WHERE email = #{email} AND deleted = 0 LIMIT 1 FOR UPDATE")
    EmailVerification selectByEmailForUpdate(@Param("email") String email);

    /** 事务外短查询预检票据，事务内仍会再次加锁与 CAS。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_email_verification "
            + "WHERE ticket_hash = #{ticketHash} AND deleted = 0 LIMIT 1")
    EmailVerification selectByTicketHash(@Param("ticketHash") String ticketHash);

    /**
     * 用票据摘要锁定验证行，确保一个票据最多完成一次注册。
     *
     * @param ticketHash 票据 SHA-256 摘要
     * @return 验证状态，不存在时返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_email_verification "
            + "WHERE ticket_hash = #{ticketHash} AND deleted = 0 LIMIT 1 FOR UPDATE")
    EmailVerification selectByTicketHashForUpdate(@Param("ticketHash") String ticketHash);

    /**
     * 原子消费仍有效的注册票据，并清除可用于再次定位票据的摘要。
     *
     * @param verificationId 验证业务标识
     * @param ticketHash 票据 SHA-256 摘要
     * @param consumedAt 消费时间
     * @return 更新行数，1 表示消费成功，0 表示过期或已被消费
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_email_verification SET status = 'CONSUMED', consumed_at = #{consumedAt}, "
            + "ticket_hash = NULL, ticket_expires_at = NULL, code_hmac = NULL, "
            + "code_delivery_status = 'NONE', attempts_remaining = 0, "
            + "updated_at = #{consumedAt}, "
            + "lock_version = lock_version + 1 "
            + "WHERE verification_id = #{verificationId} AND ticket_hash = #{ticketHash} "
            + "AND status = 'VERIFIED' AND ticket_expires_at > #{consumedAt} AND deleted = 0")
    int consumeVerifiedTicket(@Param("verificationId") String verificationId,
                              @Param("ticketHash") String ticketHash,
                              @Param("consumedAt") LocalDateTime consumedAt);

    /**
     * SMTP 成功后精确确认本次验证码已交付。
     *
     * <p>必须仍处于 ISSUING、仍是同一业务标识与 HMAC 且尚未绝对过期；否则返回 0，调用方
     * 失败关闭，不得向客户端宣称验证码可用。
     *
     * @return 1 表示交付状态确认成功，0 表示状态已经变化或验证码已过期
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_email_verification SET code_delivery_status = 'DELIVERED', "
            + "updated_at = #{deliveredAt}, lock_version = lock_version + 1 "
            + "WHERE verification_id = #{verificationId} AND code_hmac = #{codeHmac} "
            + "AND code_delivery_status = 'ISSUING' AND expires_at > #{deliveredAt} AND deleted = 0")
    int markChallengeDelivered(@Param("verificationId") String verificationId,
                               @Param("codeHmac") String codeHmac,
                               @Param("deliveredAt") LocalDateTime deliveredAt);

    /**
     * SMTP 失败后精确撤销仍等于本次标识与 HMAC 的 challenge。
     *
     * <p>并存旧票据在补偿发生时仍有效则保留 VERIFIED；否则转为 INVALIDATED。WHERE 的双重
     * CAS 使迟到失败不能覆盖并行轮换的新码或已经成功替换的新票据。
     *
     * @return 1 表示撤销本次 challenge，0 表示状态已经向前推进
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_email_verification SET code_hmac = NULL, "
            + "code_delivery_status = 'NONE', attempts_remaining = 0, "
            + "status = CASE WHEN status = 'VERIFIED' AND ticket_hash IS NOT NULL "
            + "AND ticket_expires_at > #{cancelledAt} AND consumed_at IS NULL "
            + "THEN 'VERIFIED' ELSE 'INVALIDATED' END, updated_at = #{cancelledAt}, "
            + "lock_version = lock_version + 1 "
            + "WHERE verification_id = #{verificationId} AND code_hmac = #{codeHmac} "
            + "AND code_delivery_status = 'ISSUING' AND deleted = 0")
    int cancelIssuedChallenge(@Param("verificationId") String verificationId,
                              @Param("codeHmac") String codeHmac,
                              @Param("cancelledAt") LocalDateTime cancelledAt);

    /**
     * 分批物理删除已超过保留期的验证状态。
     *
     * <p>候选子查询与外层 DELETE 都携带状态和时间条件，即使候选选择后发生并发状态迁移，
     * 也不会误删刚被验证或消费的记录。
     *
     * @return 实际删除行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM t_kb_email_verification WHERE deleted = 0 AND ("
            + "(status = 'ISSUED' AND expires_at <= #{issuedExpiredBefore}) OR "
            + "(status = 'VERIFIED' AND ticket_expires_at IS NOT NULL "
            + "AND ticket_expires_at <= #{verifiedTicketExpiredBefore} "
            + "AND (code_hmac IS NULL OR expires_at <= #{activeCodeAt})) OR "
            + "(status = 'INVALIDATED' AND updated_at <= #{terminalBefore}) OR "
            + "(status = 'CONSUMED' AND consumed_at IS NOT NULL AND consumed_at <= #{terminalBefore})) "
            + "AND id IN (SELECT candidate.id FROM (SELECT id FROM t_kb_email_verification "
            + "WHERE deleted = 0 AND ((status = 'ISSUED' AND expires_at <= #{issuedExpiredBefore}) OR "
            + "(status = 'VERIFIED' AND ticket_expires_at IS NOT NULL "
            + "AND ticket_expires_at <= #{verifiedTicketExpiredBefore} "
            + "AND (code_hmac IS NULL OR expires_at <= #{activeCodeAt})) OR "
            + "(status = 'INVALIDATED' AND updated_at <= #{terminalBefore}) OR "
            + "(status = 'CONSUMED' AND consumed_at IS NOT NULL AND consumed_at <= #{terminalBefore})) "
            + "ORDER BY id LIMIT #{batchSize}) candidate)")
    int deleteExpiredBatch(@Param("issuedExpiredBefore") LocalDateTime issuedExpiredBefore,
                           @Param("verifiedTicketExpiredBefore") LocalDateTime verifiedTicketExpiredBefore,
                           @Param("activeCodeAt") LocalDateTime activeCodeAt,
                           @Param("terminalBefore") LocalDateTime terminalBefore,
                           @Param("batchSize") int batchSize);
}
