package io.kbrag.domain.registration;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import io.kbrag.domain.entity.EmailVerification;
import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.mapper.EmailVerificationMapper;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册持久化的安全契约，防止后续重构把凭据带入日志或丢掉行锁/CAS 条件。
 *
 * @author owlzhangfq@gmail.com
 */
class RegistrationPersistenceContractTest {

    @Test
    void sensitiveValuesMustNotAppearInEntityToString() {
        EmailVerification verification = new EmailVerification();
        verification.setEmail("person@example.com");
        verification.setCodeHmac("secret-code-hmac");
        verification.setTicketHash("secret-ticket-hash");
        verification.setRequestIpHash("secret-ip-hash");

        RegistrationApplication application = new RegistrationApplication();
        application.setEmail("person@example.com");
        application.setPasswordHash("secret-password-hash");

        MailOutbox outbox = new MailOutbox();
        outbox.setSubject("审核结果");
        outbox.setBody("sensitive-mail-body");

        assertThat(verification.toString())
                .doesNotContain("secret-code-hmac", "secret-ticket-hash", "secret-ip-hash");
        assertThat(application.toString()).doesNotContain("secret-password-hash");
        assertThat(outbox.toString()).doesNotContain("sensitive-mail-body");
    }

    @Test
    void nullableVerificationSecretsMustUseAlwaysUpdateStrategy() throws Exception {
        assertAlwaysUpdate(EmailVerification.class.getDeclaredField("codeHmac"));
        assertAlwaysUpdate(EmailVerification.class.getDeclaredField("ticketHash"));
        assertAlwaysUpdate(EmailVerification.class.getDeclaredField("ticketExpiresAt"));
        assertAlwaysUpdate(EmailVerification.class.getDeclaredField("verifiedAt"));
        assertAlwaysUpdate(EmailVerification.class.getDeclaredField("consumedAt"));
    }

    @Test
    void rejectedApplicationCanClearOptionalFieldsBeforeResubmission() throws Exception {
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("teamName"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("applicationNote"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("reviewedBy"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("reviewedAt"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("reviewReason"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("approvedTenantId"));
        assertAlwaysUpdate(RegistrationApplication.class.getDeclaredField("approvedUserId"));
    }

    @Test
    void verificationLocksAndConsumesOnlyAValidOneTimeTicket() throws Exception {
        Method initialize = EmailVerificationMapper.class.getMethod("insertIfAbsent", EmailVerification.class);
        Method byEmail = EmailVerificationMapper.class.getMethod("selectByEmailForUpdate", String.class);
        Method byTicket = EmailVerificationMapper.class.getMethod("selectByTicketHashForUpdate", String.class);
        Method consume = EmailVerificationMapper.class.getMethod("consumeVerifiedTicket",
                String.class, String.class, LocalDateTime.class);

        assertThat(insertSql(initialize))
                .contains("code_delivery_status", "#{codeDeliveryStatus}",
                        "ON DUPLICATE KEY UPDATE id = id", "CURRENT_TIMESTAMP",
                        "lock_version, deleted");
        assertThat(selectSql(byEmail)).contains("email = #{email}", "deleted = 0", "FOR UPDATE");
        assertThat(selectSql(byTicket)).contains("ticket_hash = #{ticketHash}", "FOR UPDATE");
        assertThat(updateSql(consume))
                .contains("status = 'VERIFIED'", "ticket_expires_at > #{consumedAt}",
                        "status = 'CONSUMED'", "ticket_hash = NULL", "code_hmac = NULL",
                        "code_delivery_status = 'NONE'",
                        "attempts_remaining = 0", "lock_version = lock_version + 1");
    }

    @Test
    void smtpSuccessMustConfirmOnlyTheExactUnexpiredIssuingChallenge() throws Exception {
        Method delivered = EmailVerificationMapper.class.getMethod("markChallengeDelivered",
                String.class, String.class, LocalDateTime.class);

        assertThat(updateSql(delivered))
                .contains("code_delivery_status = 'DELIVERED'",
                        "verification_id = #{verificationId}", "code_hmac = #{codeHmac}",
                        "code_delivery_status = 'ISSUING'", "expires_at > #{deliveredAt}",
                        "lock_version = lock_version + 1");
    }

    @Test
    void failedMailCompensationMustCasTheExactChallengeAndPreserveOnlyAnActiveTicket()
            throws Exception {
        Method cancel = EmailVerificationMapper.class.getMethod("cancelIssuedChallenge",
                String.class, String.class, LocalDateTime.class);

        assertThat(updateSql(cancel))
                .contains("code_hmac = NULL", "code_delivery_status = 'NONE'",
                        "attempts_remaining = 0",
                        "status = CASE WHEN status = 'VERIFIED'",
                        "ticket_hash IS NOT NULL", "ticket_expires_at > #{cancelledAt}",
                        "consumed_at IS NULL", "THEN 'VERIFIED' ELSE 'INVALIDATED' END",
                        "verification_id = #{verificationId}", "code_hmac = #{codeHmac}",
                        "code_delivery_status = 'ISSUING'",
                        "lock_version = lock_version + 1");
    }

    @Test
    void verificationRetentionMustRecheckStateAndPreserveAnActiveParallelChallenge()
            throws Exception {
        Method cleanup = EmailVerificationMapper.class.getMethod("deleteExpiredBatch",
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class,
                LocalDateTime.class, int.class);

        String sql = deleteSql(cleanup);

        assertThat(sql)
                .contains("status = 'ISSUED'", "expires_at <= #{issuedExpiredBefore}",
                        "status = 'VERIFIED'", "ticket_expires_at <= #{verifiedTicketExpiredBefore}",
                        "code_hmac IS NULL OR expires_at <= #{activeCodeAt}",
                        "status = 'INVALIDATED'", "updated_at <= #{terminalBefore}",
                        "status = 'CONSUMED'", "consumed_at <= #{terminalBefore}",
                        "ORDER BY id LIMIT #{batchSize}");
        assertThat(count(sql, "status = 'VERIFIED'")).isEqualTo(2);
        assertThat(count(sql, "code_hmac IS NULL OR expires_at <= #{activeCodeAt}")).isEqualTo(2);
    }

    @Test
    void reviewTransitionsArePendingOnlyAndErasePasswordHash() throws Exception {
        Method lock = RegistrationApplicationMapper.class
                .getMethod("selectByApplicationIdForUpdate", String.class);
        Method approve = RegistrationApplicationMapper.class.getMethod("markApproved",
                String.class, String.class, LocalDateTime.class, String.class, String.class);
        Method reject = RegistrationApplicationMapper.class.getMethod("markRejected",
                String.class, String.class, LocalDateTime.class, String.class);

        assertThat(selectSql(lock)).contains("application_id = #{applicationId}", "FOR UPDATE");
        assertThat(updateSql(approve))
                .contains("status = 'APPROVED'", "status = 'PENDING'", "password_hash = NULL");
        assertThat(updateSql(reject))
                .contains("status = 'REJECTED'", "status = 'PENDING'", "password_hash = NULL");
    }

    @Test
    void pendingRetentionMustUseAKeysetCursorAndConditionalPasswordErasure()
            throws Exception {
        Method candidates = RegistrationApplicationMapper.class.getMethod(
                "selectExpiredPendingBatch", LocalDateTime.class, long.class, int.class);
        Method expire = RegistrationApplicationMapper.class.getMethod(
                "expirePendingIfEligible", String.class, LocalDateTime.class,
                LocalDateTime.class, String.class);

        assertThat(selectSql(candidates))
                .contains("status = 'PENDING'", "created_at <= #{createdBefore}",
                        "id > #{afterId}", "ORDER BY id LIMIT #{batchSize}");
        assertThat(updateSql(expire))
                .contains("status = 'REJECTED'", "reviewed_by = NULL",
                        "review_reason = #{reviewReason}", "password_hash = NULL",
                        "application_id = #{applicationId}", "status = 'PENDING'",
                        "created_at <= #{createdBefore}", "lock_version = lock_version + 1");
    }

    @Test
    void outboxClaimIsBoundedRetryAwareAndSkipsRowsLockedByOtherInstances() throws Exception {
        Method backlog = MailOutboxMapper.class.getMethod("countDeliverableBacklog", int.class);
        Method claim = MailOutboxMapper.class.getMethod("selectReadyBatch",
                LocalDateTime.class, int.class, int.class);
        Method lease = MailOutboxMapper.class.getMethod("claimDeliveryLease",
                String.class, LocalDateTime.class, LocalDateTime.class, int.class, int.class);
        Method sent = MailOutboxMapper.class.getMethod("markSent",
                String.class, LocalDateTime.class, int.class);
        Method failed = MailOutboxMapper.class.getMethod("markFailed",
                String.class, int.class, LocalDateTime.class, String.class, int.class);

        assertThat(selectSql(backlog))
                .contains("status IN ('PENDING', 'FAILED')", "retry_count < #{maxRetries}",
                        "next_retry_at IS NOT NULL");
        assertThat(selectSql(claim))
                .contains("retry_count < #{maxRetries}", "next_retry_at <= #{readyAt}",
                        "LIMIT #{limit}", "FOR UPDATE SKIP LOCKED");
        assertThat(updateSql(lease))
                .contains("next_retry_at = #{leaseUntil}", "next_retry_at <= #{readyAt}",
                        "lock_version = lock_version + 1",
                        "lock_version = #{expectedLockVersion}");
        assertThat(updateSql(sent)).contains("lock_version = #{expectedLockVersion}", "status = 'SENT'");
        assertThat(updateSql(failed)).contains("lock_version = #{expectedLockVersion}", "status = 'FAILED'");
    }

    private void assertAlwaysUpdate(Field field) {
        assertThat(field.getAnnotation(TableField.class).updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
    }

    private String selectSql(Method method) {
        return String.join(" ", method.getAnnotation(Select.class).value());
    }

    private String insertSql(Method method) {
        return String.join(" ", method.getAnnotation(Insert.class).value());
    }

    private String updateSql(Method method) {
        return String.join(" ", method.getAnnotation(Update.class).value());
    }

    private String deleteSql(Method method) {
        return String.join(" ", method.getAnnotation(Delete.class).value());
    }

    private int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
