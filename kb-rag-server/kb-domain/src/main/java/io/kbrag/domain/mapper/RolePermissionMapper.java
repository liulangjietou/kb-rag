package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.RolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_role_permission.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /**
     * Physically removes every grant of one role.
     *
     * <p>Hand written because the generated delete honours the logical delete flag, and a rebind that
     * only flagged the old rows would leave them behind for the next read to union back in. Grants are
     * configuration, not evidence: there is nothing here worth keeping a tombstone of.
     *
     * @param roleId role business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);
}
