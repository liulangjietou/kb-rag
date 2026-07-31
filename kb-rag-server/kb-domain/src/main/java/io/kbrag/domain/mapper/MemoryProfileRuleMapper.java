package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MemoryProfileRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_memory_profile_rule.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MemoryProfileRuleMapper extends BaseMapper<MemoryProfileRule> {
}
