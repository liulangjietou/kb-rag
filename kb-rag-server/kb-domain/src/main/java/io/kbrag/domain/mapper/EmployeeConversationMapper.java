package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.model.EmployeeConversationScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 会话根资源显式三重归属校验，后台线程也不依赖请求租户插件。 */
@Mapper
public interface EmployeeConversationMapper extends BaseMapper<EmployeeConversation> {

    /** 短事务串行化同一会话的接受、保存、停止和恢复；模型调用不得持有此锁。 */
    @Select("SELECT * FROM t_kb_conversation WHERE conversation_id = #{conversationId} "
            + "AND tenant_id = #{scope.tenantId} AND user_id = #{scope.userId} "
            + "AND app_id = #{scope.appId} AND deleted = 0 FOR UPDATE")
    EmployeeConversation lockOwned(@Param("scope") EmployeeConversationScope scope,
                                   @Param("conversationId") String conversationId);

    /** 数据库分页前搜索标题与用户原问题，不检索可能已经撤权的回答正文。 */
    @Select("""
            <script>
            SELECT c.* FROM t_kb_conversation c
            WHERE c.tenant_id = #{scope.tenantId} AND c.user_id = #{scope.userId}
              AND c.app_id = #{scope.appId} AND c.deleted = 0
            <if test="pattern != null">
              AND (c.title LIKE #{pattern} ESCAPE '!'
                OR EXISTS (SELECT 1 FROM t_kb_conversation_run r
                  WHERE r.conversation_id = c.conversation_id AND r.tenant_id = c.tenant_id
                    AND r.user_id = c.user_id AND r.app_id = c.app_id AND r.deleted = 0
                    AND r.question LIKE #{pattern} ESCAPE '!'))
            </if>
            ORDER BY c.last_activity_at DESC, c.id DESC
            </script>
            """)
    IPage<EmployeeConversation> listOwned(Page<EmployeeConversation> page,
                                           @Param("scope") EmployeeConversationScope scope,
                                           @Param("pattern") String pattern);
}
