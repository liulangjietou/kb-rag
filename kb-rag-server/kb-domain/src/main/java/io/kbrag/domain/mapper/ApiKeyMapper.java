package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_api_key.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
