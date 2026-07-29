package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ExtSourceItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_ext_source_item.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExtSourceItemMapper extends BaseMapper<ExtSourceItem> {

    /**
     * Physically removes every item row of one source.
     *
     * <p>Hard delete for the same reason as the source row: a soft-deleted item would hold
     * {@code uk_source_object} hostage if the same source id were ever minted again, and item rows
     * are pure sync bookkeeping with no lifecycle of their own to preserve.
     */
    @Delete("DELETE FROM t_kb_ext_source_item WHERE source_id = #{sourceId}")
    int hardDeleteBySourceId(@Param("sourceId") String sourceId);
}
