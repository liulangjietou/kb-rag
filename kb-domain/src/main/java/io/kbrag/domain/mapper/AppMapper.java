package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.App;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_app.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AppMapper extends BaseMapper<App> {
}
