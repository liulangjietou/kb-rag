package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ModelUsageMonthly;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Atomic reservation and settlement of a tenant month.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ModelUsageMonthlyMapper extends BaseMapper<ModelUsageMonthly> {

    /** Creates the counter row once; concurrent first calls are absorbed by INSERT IGNORE. */
    @Insert("""
            INSERT IGNORE INTO t_kb_model_usage_monthly
                (tenant_id, usage_month, used_tokens, reserved_tokens)
            VALUES (#{tenantId}, #{usageMonth}, 0, 0)
            """)
    int ensure(@Param("tenantId") String tenantId, @Param("usageMonth") String usageMonth);

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
