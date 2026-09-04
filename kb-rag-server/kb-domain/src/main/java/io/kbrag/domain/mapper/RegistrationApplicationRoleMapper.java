package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RegistrationApplicationRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注册审核角色快照的数据访问。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RegistrationApplicationRoleMapper extends BaseMapper<RegistrationApplicationRole> {
}
