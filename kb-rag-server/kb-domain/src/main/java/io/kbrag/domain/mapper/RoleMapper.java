package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_role.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
