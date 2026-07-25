package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_admin_user.
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
