package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.IndexRegistry;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_index_registry.
 */
@Mapper
public interface IndexRegistryMapper extends BaseMapper<IndexRegistry> {
}
