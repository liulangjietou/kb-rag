package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Annotation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_annotation.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AnnotationMapper extends BaseMapper<Annotation> {
}
