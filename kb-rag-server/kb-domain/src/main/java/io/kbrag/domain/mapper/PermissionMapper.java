package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_permission.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
