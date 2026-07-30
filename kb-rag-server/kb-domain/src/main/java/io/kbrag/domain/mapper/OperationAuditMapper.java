package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.OperationAudit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Persistence port of the operation audit trail.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OperationAuditMapper extends BaseMapper<OperationAudit> {

    /**
     * Physically removes expired rows, oldest first up to the batch limit.
     *
     * <p>A hard delete rather than the logical one on purpose: an audit row past its retention window
     * must actually leave the disk - the M16 contract aligns the operation trail with the 180 day API
     * audit discipline, and this table is never archived.
     *
     * <p>The row limit is the caller's batch size, so one statement never holds a long transaction over
     * a table the request path keeps inserting into.
     *
     * @param before exclusive upper bound of {@code created_at}, the retention horizon
     * @param limit  maximum rows deleted by this statement
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_operation_audit WHERE created_at < #{before} LIMIT #{limit}")
    int purgeExpired(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
