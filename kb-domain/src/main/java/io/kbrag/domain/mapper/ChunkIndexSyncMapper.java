package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ChunkIndexSync;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_chunk_index_sync.
 */
@Mapper
public interface ChunkIndexSyncMapper extends BaseMapper<ChunkIndexSync> {
}
