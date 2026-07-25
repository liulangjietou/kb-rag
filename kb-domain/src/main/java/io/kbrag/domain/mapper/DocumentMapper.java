package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_document.
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
