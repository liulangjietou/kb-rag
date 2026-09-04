package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RegistrationApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 注册申请的数据访问。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RegistrationApplicationMapper extends BaseMapper<RegistrationApplication> {

    /**
     * 按业务标识锁定申请，串行化管理员审核。
     *
     * @param applicationId 申请业务标识
     * @return 申请，不存在时返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_registration_application "
            + "WHERE application_id = #{applicationId} AND deleted = 0 LIMIT 1 FOR UPDATE")
    RegistrationApplication selectByApplicationIdForUpdate(@Param("applicationId") String applicationId);

    /** 按客户端幂等标识读取已提交回执。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_registration_application "
            + "WHERE submission_id = #{submissionId} AND deleted = 0 LIMIT 1")
    RegistrationApplication selectBySubmissionId(@Param("submissionId") String submissionId);

    /**
     * 按邮箱锁定申请，串行化重复提交或重新申请。
     *
     * @param email 标准化邮箱
     * @return 申请，不存在时返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_registration_application "
            + "WHERE email = #{email} AND deleted = 0 ORDER BY id DESC LIMIT 1 FOR UPDATE")
    RegistrationApplication selectByEmailForUpdate(@Param("email") String email);

    /**
     * 把待审核申请原子推进为通过，并清除已无用途的密码摘要。
     *
     * @return 更新行数，0 表示已被其他审核人处理
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_registration_application SET status = 'APPROVED', reviewed_by = #{reviewedBy}, "
            + "reviewed_at = #{reviewedAt}, review_reason = NULL, approved_tenant_id = #{approvedTenantId}, "
            + "approved_user_id = #{approvedUserId}, password_hash = NULL, updated_at = #{reviewedAt}, "
            + "lock_version = lock_version + 1 "
            + "WHERE application_id = #{applicationId} AND status = 'PENDING' AND deleted = 0")
    int markApproved(@Param("applicationId") String applicationId,
                     @Param("reviewedBy") String reviewedBy,
                     @Param("reviewedAt") LocalDateTime reviewedAt,
                     @Param("approvedTenantId") String approvedTenantId,
                     @Param("approvedUserId") String approvedUserId);

    /**
     * 把待审核申请原子推进为拒绝，并清除已无用途的密码摘要。
     *
     * @return 更新行数，0 表示已被其他审核人处理
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_registration_application SET status = 'REJECTED', reviewed_by = #{reviewedBy}, "
            + "reviewed_at = #{reviewedAt}, review_reason = #{reviewReason}, approved_tenant_id = NULL, "
            + "approved_user_id = NULL, password_hash = NULL, updated_at = #{reviewedAt}, "
            + "lock_version = lock_version + 1 "
            + "WHERE application_id = #{applicationId} AND status = 'PENDING' AND deleted = 0")
    int markRejected(@Param("applicationId") String applicationId,
                     @Param("reviewedBy") String reviewedBy,
                     @Param("reviewedAt") LocalDateTime reviewedAt,
                     @Param("reviewReason") String reviewReason);

    /** 以主键游标读取一批超期候选；毒行只影响自身，后续候选仍可在本轮继续处理。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, application_id FROM t_kb_registration_application "
            + "WHERE deleted = 0 AND status = 'PENDING' AND created_at <= #{createdBefore} "
            + "AND id > #{afterId} "
            + "ORDER BY id LIMIT #{batchSize}")
    List<RegistrationApplication> selectExpiredPendingBatch(
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("afterId") long afterId,
            @Param("batchSize") int batchSize);

    /**
     * 在行锁事务内条件关闭一个超期待审核申请，并清除密码摘要。
     *
     * <p>WHERE 再次要求 {@code PENDING} 与创建时间截止条件，人工审核和系统关闭并发时
     * 只有一方能够完成状态迁移。
     *
     * @return 1 表示关闭成功，0 表示状态已变化或不再满足期限
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE t_kb_registration_application SET status = 'REJECTED', reviewed_by = NULL, "
            + "reviewed_at = #{reviewedAt}, review_reason = #{reviewReason}, "
            + "approved_tenant_id = NULL, approved_user_id = NULL, password_hash = NULL, "
            + "updated_at = #{reviewedAt}, lock_version = lock_version + 1 "
            + "WHERE application_id = #{applicationId} AND deleted = 0 AND status = 'PENDING' "
            + "AND created_at <= #{createdBefore}")
    int expirePendingIfEligible(@Param("applicationId") String applicationId,
                                @Param("createdBefore") LocalDateTime createdBefore,
                                @Param("reviewedAt") LocalDateTime reviewedAt,
                                @Param("reviewReason") String reviewReason);
}
