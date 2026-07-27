package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.SourceMapping;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_source_mapping.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SourceMappingMapper extends BaseMapper<SourceMapping> {
}
