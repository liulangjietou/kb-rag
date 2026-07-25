package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.IkDict;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_ik_dict.
 */
@Mapper
public interface IkDictMapper extends BaseMapper<IkDict> {
}
