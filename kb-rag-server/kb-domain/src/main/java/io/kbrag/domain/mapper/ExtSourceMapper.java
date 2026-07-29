package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ExtSource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_ext_source.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExtSourceMapper extends BaseMapper<ExtSource> {

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
