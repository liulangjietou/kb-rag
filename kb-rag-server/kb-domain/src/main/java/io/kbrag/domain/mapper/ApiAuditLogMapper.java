package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.ApiAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Data access for t_kb_api_audit_log.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApiAuditLogMapper extends BaseMapper<ApiAuditLog> {

    /**
     * Physically removes the rows an archive batch already wrote to object storage.
     *
     * <p><b>Why a hand written statement and not {@code delete}.</b> The generated delete honours the
     * logical delete flag, which would leave the rows in the table forever: an audit table grows without
     * bound by design, so the retention policy has to actually reclaim the space. The archive object is
     * written and verified before this runs, which is what makes the physical delete safe.
     *
     * <p>The row limit is the caller's batch size, so one statement never holds a long transaction over a
     * table the request path is still inserting into.
     *
     * @param maxId inclusive upper bound of the ids that were archived
     * @param before exclusive upper bound of {@code created_at}, the retention horizon
     * @param limit  maximum rows deleted by this statement
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_api_audit_log WHERE id <= #{maxId} AND created_at < #{before} LIMIT #{limit}")
    int purgeArchived(@Param("maxId") long maxId,
                      @Param("before") LocalDateTime before,
                      @Param("limit") int limit);
}
