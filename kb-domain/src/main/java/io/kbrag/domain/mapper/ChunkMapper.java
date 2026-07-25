package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Chunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_chunk.
 */
@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {
}
