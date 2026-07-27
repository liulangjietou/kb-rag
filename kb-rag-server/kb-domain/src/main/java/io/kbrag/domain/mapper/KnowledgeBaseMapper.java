package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_knowledge_base.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
