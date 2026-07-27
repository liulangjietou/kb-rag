package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.KbTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_task.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface KbTaskMapper extends BaseMapper<KbTask> {
}
