package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Data access for t_kb_admin_user.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    /**
     * Finds an account by login name across every tenant.
     *
     * <p>The login name is globally unique - the M16 contract keeps one namespace so an account can be
     * moved between tenants without renaming - which makes the uniqueness check of a creation a global
     * question. A fenced query would only see the caller's own tenant and let the insert run into the
     * database unique key instead of a readable error.
     *
     * @param username normalised login name
     * @return account record, or {@code null} when the name is free
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_admin_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    AdminUser selectByUsernameAcrossTenants(@Param("username") String username);
}
