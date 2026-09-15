package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RoleAppScope;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 角色应用范围的关联存储；调用方须先授权所属根资源。 */
@Mapper
public interface RoleAppScopeMapper extends BaseMapper<RoleAppScope> {

    /** 替换范围或删除角色时清理旧关联，避免已撤销范围恢复。 */
    @Delete("DELETE FROM t_kb_role_app WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);

    /** 应用删除时清理所有角色对此应用的关联。 */
    @Delete("DELETE FROM t_kb_role_app WHERE app_id = #{appId}")
    int deleteByAppId(@Param("appId") String appId);
}
