package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MemoryAppKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_memory_app_key.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MemoryAppKeyMapper extends BaseMapper<MemoryAppKey> {
}
