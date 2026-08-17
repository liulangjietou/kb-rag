package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ModelUsage;
import io.kbrag.domain.model.ModelCostTotal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Data access for the model usage ledger.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsage> {

    /** Cost totals stay split by currency; unlike tokens, unlike currencies must never be added. */
    @Select("""
            SELECT currency, SUM(cost_micros) AS cost_micros
            FROM t_kb_model_usage
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{monthStart}
              AND created_at < #{nextMonthStart}
              AND status = 'SUCCEEDED'
              AND priced = 1
              AND deleted = 0
            GROUP BY currency
            ORDER BY currency
            """)
    List<ModelCostTotal> sumCostByCurrency(@Param("tenantId") String tenantId,
                                           @Param("monthStart") java.time.LocalDateTime monthStart,
                                           @Param("nextMonthStart") java.time.LocalDateTime nextMonthStart);

    /** Counts successful calls whose provider response had no token counters. */
    @Select("""
            SELECT COUNT(*)
            FROM t_kb_model_usage
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{monthStart}
              AND created_at < #{nextMonthStart}
              AND status = 'SUCCEEDED'
              AND estimated = 1
              AND deleted = 0
            """)
    long countEstimated(@Param("tenantId") String tenantId,
                        @Param("monthStart") java.time.LocalDateTime monthStart,
                        @Param("nextMonthStart") java.time.LocalDateTime nextMonthStart);

    /** Counts successful calls that had no active price snapshot. */
    @Select("""
            SELECT COUNT(*)
            FROM t_kb_model_usage
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{monthStart}
              AND created_at < #{nextMonthStart}
              AND status = 'SUCCEEDED'
              AND priced = 0
              AND deleted = 0
            """)
    long countUnpriced(@Param("tenantId") String tenantId,
                       @Param("monthStart") java.time.LocalDateTime monthStart,
                       @Param("nextMonthStart") java.time.LocalDateTime nextMonthStart);
}
