package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EvalRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_eval_run.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRun> {
}
