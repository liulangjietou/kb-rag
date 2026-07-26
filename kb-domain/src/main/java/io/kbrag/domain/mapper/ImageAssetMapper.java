package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ImageAsset;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access of {@code t_kb_image_asset}.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ImageAssetMapper extends BaseMapper<ImageAsset> {
}
