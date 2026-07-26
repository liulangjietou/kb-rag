package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EvalResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_eval_result.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResult> {
}
