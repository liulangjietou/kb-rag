package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MailOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可靠邮件发件箱的数据访问。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MailOutboxMapper extends BaseMapper<MailOutbox> {

    /** 统计仍需自动投递的 PENDING 与可重试 FAILED 任务。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM t_kb_mail_outbox "
            + "WHERE status IN ('PENDING', 'FAILED') AND retry_count < #{maxRetries} "
            + "AND next_retry_at IS NOT NULL AND deleted = 0")
    long countDeliverableBacklog(@Param("maxRetries") int maxRetries);

    /**
     * 锁定一批到期任务；跳过其他实例已持有的行，支持多实例并发派发。
     *
     * @param readyAt 到期时间上界
     * @param limit 批量上限
     * @param maxRetries 最大失败次数，达到上限的任务留待人工处理
     * @return 当前实例取得的任务
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_mail_outbox "
            + "WHERE status IN ('PENDING', 'FAILED') AND retry_count < #{maxRetries} "
            + "AND next_retry_at IS NOT NULL "
            + "AND next_retry_at <= #{readyAt} AND deleted = 0 "
            + "ORDER BY next_retry_at, id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<MailOutbox> selectReadyBatch(@Param("readyAt") LocalDateTime readyAt,
                                      @Param("limit") int limit,
                                      @Param("maxRetries") int maxRetries);

    /**
     * 用下一次重试时间作为有界投递 lease，并推进版本号。
     *
     * <p>事务提交后不再持有行锁；lease 到期前其他实例不可重复取得任务。进程在 SMTP
     * 完成前崩溃时，任务会在 lease 到期后重新进入 ready 集合。
     *
     * @return 1 表示本实例取得 lease，0 表示任务已被其他实例推进
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_mail_outbox SET next_retry_at = #{leaseUntil}, updated_at = #{readyAt}, "
            + "lock_version = lock_version + 1 WHERE outbox_id = #{outboxId} "
            + "AND status IN ('PENDING', 'FAILED') AND retry_count < #{maxRetries} "
            + "AND next_retry_at IS NOT NULL AND next_retry_at <= #{readyAt} "
            + "AND lock_version = #{expectedLockVersion} AND deleted = 0")
    int claimDeliveryLease(@Param("outboxId") String outboxId,
                           @Param("readyAt") LocalDateTime readyAt,
                           @Param("leaseUntil") LocalDateTime leaseUntil,
                           @Param("maxRetries") int maxRetries,
                           @Param("expectedLockVersion") int expectedLockVersion);

    /**
     * 以乐观锁标记发送成功。
     *
     * @return 更新行数，0 表示状态已被其他实例推进
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_mail_outbox SET status = 'SENT', sent_at = #{sentAt}, next_retry_at = NULL, "
            + "last_error = NULL, updated_at = #{sentAt}, lock_version = lock_version + 1 "
            + "WHERE outbox_id = #{outboxId} AND status IN ('PENDING', 'FAILED') "
            + "AND lock_version = #{expectedLockVersion} AND deleted = 0")
    int markSent(@Param("outboxId") String outboxId,
                 @Param("sentAt") LocalDateTime sentAt,
                 @Param("expectedLockVersion") int expectedLockVersion);

    /**
     * 以乐观锁记录一次失败以及下一次重试时间。
     *
     * @return 更新行数，0 表示状态已被其他实例推进
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_mail_outbox SET status = 'FAILED', retry_count = #{retryCount}, "
            + "next_retry_at = #{nextRetryAt}, last_error = #{lastError}, updated_at = CURRENT_TIMESTAMP, "
            + "lock_version = lock_version + 1 "
            + "WHERE outbox_id = #{outboxId} AND status IN ('PENDING', 'FAILED') "
            + "AND lock_version = #{expectedLockVersion} AND deleted = 0")
    int markFailed(@Param("outboxId") String outboxId,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("lastError") String lastError,
                   @Param("expectedLockVersion") int expectedLockVersion);
}
