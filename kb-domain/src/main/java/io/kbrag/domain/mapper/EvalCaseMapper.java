package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EvalCase;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_eval_case.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCase> {
}
