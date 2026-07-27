package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_system_config.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}
