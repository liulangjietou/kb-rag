package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.domain.entity.EmployeeConversationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 仅向质量维护入口列出主动评价为 BAD 的完整回答，不提供员工会话浏览能力。 */
@Mapper
public interface EmployeeFeedbackMapper extends BaseMapper<EmployeeConversationRun> {
    /** 租户、存活的会话与应用、应用范围和运行时知识库绑定均在分页及计数前过滤。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT r.run_id FROM t_kb_conversation_run r
            JOIN t_kb_conversation c ON c.conversation_id = r.conversation_id
              AND c.tenant_id = r.tenant_id AND c.user_id = r.user_id AND c.app_id = r.app_id AND c.deleted = 0
            JOIN t_kb_app a ON a.app_id = r.app_id AND a.tenant_id = r.tenant_id AND a.deleted = 0
            WHERE r.tenant_id = #{tenantId} AND r.deleted = 0
              AND r.status = 'SUCCEEDED' AND r.feedback_verdict = 'BAD'
              AND JSON_CONTAINS(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(r.target_json, '$.config')), '$.kb_refs'),
                JSON_OBJECT('kb_id', #{kbId})) = 1
            <if test="appIds != null">
              <choose>
                <when test="appIds.size() > 0">
                  AND r.app_id IN
                  <foreach collection="appIds" item="appId" open="(" separator="," close=")">#{appId}</foreach>
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
            </if>
            ORDER BY r.feedback_updated_at DESC, r.id DESC
            </script>
            """)
    IPage<EmployeeConversationRun> pageBad(IPage<EmployeeConversationRun> page,
                                          @Param("tenantId") String tenantId, @Param("kbId") String kbId,
                                          @Param("appIds") List<String> appIds);
}
