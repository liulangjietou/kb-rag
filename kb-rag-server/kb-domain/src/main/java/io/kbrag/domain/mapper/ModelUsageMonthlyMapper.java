package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ModelUsageMonthly;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Atomic reservation and settlement of a tenant month.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ModelUsageMonthlyMapper extends BaseMapper<ModelUsageMonthly> {

    /**
     * Creates the counter row once; concurrent first calls are absorbed by INSERT IGNORE.
     *
     * <p><b>Call it only when the row is known to be missing.</b> When the row already exists this
     * statement still hits the {@code uk_tenant_month} unique key, and InnoDB takes a <em>shared</em>
     * lock on the existing row to decide the duplicate. A transaction that then updates the same row
     * has to upgrade that S lock to an X lock - two concurrent callers each holding S and each waiting
     * for X deadlock rather than queue. That is exactly what one document's parallel embedding batches
     * produced, since every batch bills the same tenant month.
     */
    @Insert("""
            INSERT IGNORE INTO t_kb_model_usage_monthly
                (tenant_id, usage_month, used_tokens, reserved_tokens)
            VALUES (#{tenantId}, #{usageMonth}, 0, 0)
            """)
    int ensure(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth);

    /**
     * Tells whether the counter row of a tenant month is already there.
     *
     * <p>A plain consistent read, which takes no lock - and that is the whole reason the reservation
     * path asks this question here instead of letting {@link #ensure} answer it implicitly. Being over
     * quota is a lasting state: a tenant that has exhausted its month would otherwise pay INSERT
     * IGNORE's shared lock on every single call until the month rolls over.
     *
     * @param tenantId   tenant business id
     * @param usageMonth billing month, {@code YYYY-MM}
     * @return 1 when the row exists, 0 otherwise
     */
    @Select("""
            SELECT COUNT(1) FROM t_kb_model_usage_monthly
            WHERE tenant_id = #{tenantId} AND usage_month = #{usageMonth} AND deleted = 0
            """)
    int countRow(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth);

    /**
     * Reserves only while the tenant's live quota still covers used plus in-flight tokens.
     *
     * <p>The quota is joined, not copied into the month row, so an operator's update applies to the
     * very next call without rewriting counters. One conditional UPDATE is the concurrency gate.
     */
    @Update("""
            UPDATE t_kb_model_usage_monthly m
            JOIN t_kb_tenant t ON t.tenant_id = m.tenant_id AND t.deleted = 0
            SET m.reserved_tokens = m.reserved_tokens + #{tokens}
            WHERE m.tenant_id = #{tenantId}
              AND m.usage_month = #{usageMonth}
              AND m.deleted = 0
              AND (t.monthly_token_quota = 0
                   OR m.used_tokens + m.reserved_tokens + #{tokens} <= t.monthly_token_quota)
            """)
    int reserve(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth,
                @Param("tokens") long tokens);

    /** Moves a successful call from in-flight to used. */
    @Update("""
            UPDATE t_kb_model_usage_monthly
            SET reserved_tokens = GREATEST(0, reserved_tokens - #{reserved}),
                used_tokens = used_tokens + #{charged}
            WHERE tenant_id = #{tenantId} AND usage_month = #{usageMonth} AND deleted = 0
            """)
    int settle(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth,
               @Param("reserved") long reserved, @Param("charged") long charged);

    /** Releases the reservation of a request that never produced a usable response. */
    @Update("""
            UPDATE t_kb_model_usage_monthly
            SET reserved_tokens = GREATEST(0, reserved_tokens - #{reserved})
            WHERE tenant_id = #{tenantId} AND usage_month = #{usageMonth} AND deleted = 0
            """)
    int release(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth,
                @Param("reserved") long reserved);
}
