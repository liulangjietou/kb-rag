package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.WebSource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_web_source.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface WebSourceMapper extends BaseMapper<WebSource> {

    /**
     * Physically removes one registration row.
     *
     * <p>The inherited delete would only flip the {@code deleted} flag, and a soft-deleted row
     * still occupies {@code uk_kb_url} - re-registering the same URL after a removal would then
     * hit the unique key forever. Removal of a registration is contractually final (the document
     * stays, the binding does not), so a hard delete is the correct shape, not a workaround.
     */
    @Delete("DELETE FROM t_kb_web_source WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
