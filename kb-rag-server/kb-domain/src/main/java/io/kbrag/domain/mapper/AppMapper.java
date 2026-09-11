package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.kbrag.domain.entity.App;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Data access for t_kb_app.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AppMapper extends BaseMapper<App> {

    /**
     * 按已授权的目标租户列出名称与标识，供角色范围编辑和员工目录批量查询。
     * 显式租户条件不能省略；平台编辑其他租户角色时不受当前会话的自动租户条件干扰。
     * appIds 为 null 表示整个目标租户，空集合表示没有应用。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT app_id, tenant_id, name, description
            FROM t_kb_app
            WHERE tenant_id = #{tenantId} AND deleted = 0
            <if test="appIds != null">
              <choose>
                <when test="appIds.size() > 0">
                  AND app_id IN
                  <foreach collection="appIds" item="appId" open="(" separator="," close=")">#{appId}</foreach>
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
            </if>
            ORDER BY name, app_id
            </script>
            """)
    List<App> listInTenant(@Param("tenantId") String tenantId, @Param("appIds") List<String> appIds);
}
