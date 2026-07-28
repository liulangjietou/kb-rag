package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RetrievalFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_retrieval_feedback.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RetrievalFeedbackMapper extends BaseMapper<RetrievalFeedback> {
}
