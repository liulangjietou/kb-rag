package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MemoryLibrary;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_memory_library.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MemoryLibraryMapper extends BaseMapper<MemoryLibrary> {
}
