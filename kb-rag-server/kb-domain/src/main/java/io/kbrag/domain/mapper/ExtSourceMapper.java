package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ExtSource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Data access for t_kb_ext_source.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExtSourceMapper extends BaseMapper<ExtSource> {

    /** 时间事实独立于乐观锁状态更新，始终保存已发生的最新成功与内容变更。 */
    @Update("""
            UPDATE t_kb_ext_source SET
              last_success_at = CASE WHEN #{successAt} IS NULL THEN last_success_at
                ELSE GREATEST(COALESCE(last_success_at, #{successAt}), #{successAt}) END,
              last_content_change_at = CASE WHEN #{changeAt} IS NULL THEN last_content_change_at
                ELSE GREATEST(COALESCE(last_content_change_at, #{changeAt}), #{changeAt}) END
            WHERE id = #{id} AND deleted = 0
            """)
    int advanceHealthTimes(@Param("id") Long id, @Param("successAt") LocalDateTime successAt,
                           @Param("changeAt") LocalDateTime changeAt);

    /**
     * Physically removes one source row.
     *
     * <p>The inherited delete would only flip the {@code deleted} flag, and a soft-deleted row
     * still occupies {@code uk_kb_name} - re-registering the same name after a removal would then
     * hit the unique key forever. Removal of a registration is contractually final (the documents
     * stay, the binding does not), so a hard delete is the correct shape, not a workaround.
     */
    @Delete("DELETE FROM t_kb_ext_source WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
