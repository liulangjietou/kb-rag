package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ResourceVisit;
import io.kbrag.domain.enums.ResourceVisitKind;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 个人访问记录显式绑定租户与用户，不依赖线程租户插件补写范围。 */
@Mapper
public interface ResourceVisitMapper extends BaseMapper<ResourceVisit> {

    /** 并发重复打开只保留一条记录，迟到的写入不能把访问时间倒退。 */
    @Insert("""
            INSERT INTO t_kb_resource_visit
              (tenant_id, user_id, resource_type, resource_id, visited_at, created_at, updated_at)
            VALUES (#{tenantId}, #{userId}, #{kind}, #{resourceId}, #{visitedAt}, #{visitedAt}, #{visitedAt})
            ON DUPLICATE KEY UPDATE visited_at = GREATEST(visited_at, VALUES(visited_at)),
              updated_at = GREATEST(updated_at, VALUES(updated_at)), deleted = 0
            """)
    int remember(@Param("tenantId") String tenantId, @Param("userId") String userId,
                 @Param("kind") ResourceVisitKind kind, @Param("resourceId") String resourceId,
                 @Param("visitedAt") LocalDateTime visitedAt);

    /** 用户主动清空自己的访问记录，不影响其他人，也不删除资源。 */
    @Delete("DELETE FROM t_kb_resource_visit WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int clearOwned(@Param("tenantId") String tenantId, @Param("userId") String userId);
}
