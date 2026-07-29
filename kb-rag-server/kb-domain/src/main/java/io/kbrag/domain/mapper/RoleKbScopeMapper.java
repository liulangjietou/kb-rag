package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RoleKbScope;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_role_kb.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RoleKbScopeMapper extends BaseMapper<RoleKbScope> {

    /**
     * Physically removes the whole data scope of one role, so a rescope starts from an empty set.
     *
     * @param roleId role business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_role_kb WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);

    /**
     * Physically removes one knowledge base from every role scope, used when the base is deleted.
     *
     * <p>Without it a deleted base keeps appearing in the role editor as a checkbox nobody can explain.
     *
     * @param kbId knowledge base business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_role_kb WHERE kb_id = #{kbId}")
    int deleteByKbId(@Param("kbId") String kbId);
}
