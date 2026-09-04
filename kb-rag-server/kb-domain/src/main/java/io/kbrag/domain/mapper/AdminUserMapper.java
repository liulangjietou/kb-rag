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

    /**
     * 按联系邮箱跨租户查找存量账号。
     *
     * <p>公开注册入口没有租户上下文；这里显式绕过租户插件，防止申请人用已有账号的联系邮箱
     * 再注册一个同名邮箱账号。数据库使用大小写不敏感排序规则，因此无需在 SQL 中转换列值。
     *
     * @param email 已标准化的邮箱
     * @return 已占用该邮箱的账号，不存在时返回 {@code null}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM t_kb_admin_user WHERE email = #{email} AND deleted = 0 LIMIT 1")
    AdminUser selectByEmailAcrossTenants(@Param("email") String email);
}
