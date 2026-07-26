package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EvalDataset;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_eval_dataset.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDataset> {
}
