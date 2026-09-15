package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
