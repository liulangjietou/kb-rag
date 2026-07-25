package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_document_version.
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {
}
