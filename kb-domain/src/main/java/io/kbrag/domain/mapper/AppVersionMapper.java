package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.AppVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_app_version.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AppVersionMapper extends BaseMapper<AppVersion> {
}
