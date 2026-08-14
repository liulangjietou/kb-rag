package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ModelPrice;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for model prices.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ModelPriceMapper extends BaseMapper<ModelPrice> {
}
