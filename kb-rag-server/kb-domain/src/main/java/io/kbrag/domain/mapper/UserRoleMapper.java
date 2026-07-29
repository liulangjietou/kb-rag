package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.UserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_user_role.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * Physically removes every role held by one user, so a regrant starts from an empty set.
     *
     * @param userId user business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    /**
     * Physically removes every binding of one role, used when the role itself is deleted.
     *
     * <p>Deleting the role without this would leave holders pointing at a role that no longer resolves,
     * and the union that builds a caller's permissions would silently drop it - which looks exactly like
     * a bug in the permission check.
     *
     * @param roleId role business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_user_role WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);
}
