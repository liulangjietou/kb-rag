package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.model.EmployeeRunAddress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 问答运行的数据访问；写入需先取得所属会话行锁。 */
@Mapper
public interface EmployeeConversationRunMapper extends BaseMapper<EmployeeConversationRun> {

    /** 内部补偿扫描跨租户只返回地址，实际中断仍需重新取锁并检查更新时间。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id, user_id, app_id, conversation_id, run_id FROM t_kb_conversation_run "
            + "WHERE status IN ('PENDING', 'RUNNING') AND updated_at < #{staleBefore} AND deleted = 0 "
            + "ORDER BY updated_at, id LIMIT #{limit}")
    List<EmployeeRunAddress> staleAddresses(@Param("staleBefore") LocalDateTime staleBefore,
                                            @Param("limit") int limit);
}
