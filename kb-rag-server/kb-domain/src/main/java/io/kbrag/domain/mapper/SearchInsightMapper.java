package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.SearchInsight;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Data access for t_kb_search_insight.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SearchInsightMapper extends BaseMapper<SearchInsight> {

    /**
     * Physically removes the rows past the retention window.
     *
     * <p>A hand written statement for the same reason as the audit purge: the generated delete honours
     * the logical delete flag, which would keep every insight row forever while the retention policy
     * pretends to reclaim space. Unlike the audit trail nothing is archived first - an insight row is a
     * statistic, not evidence, and the M10 contract keeps it out of object storage on purpose.
     *
     * <p>The row limit is the caller's batch size, so one statement never holds a long transaction over
     * a table the request path keeps inserting into.
     *
     * @param before exclusive upper bound of {@code created_at}, the retention horizon
     * @param limit  maximum rows deleted by this statement
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_search_insight WHERE created_at < #{before} LIMIT #{limit}")
    int purgeExpired(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
