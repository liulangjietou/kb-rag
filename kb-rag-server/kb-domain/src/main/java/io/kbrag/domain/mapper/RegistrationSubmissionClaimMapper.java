package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 客户端提交幂等标识与票据摘要的一次性声明。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RegistrationSubmissionClaimMapper {

    /** 首个请求声明标识；并发冲突不改写原票据绑定。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO t_kb_registration_submission_claim "
            + "(submission_id, ticket_hash, created_at) VALUES "
            + "(#{submissionId}, #{ticketHash}, CURRENT_TIMESTAMP) "
            + "ON DUPLICATE KEY UPDATE submission_id = submission_id")
    int insertIfAbsent(@Param("submissionId") String submissionId,
                       @Param("ticketHash") String ticketHash);

    /** 锁定声明，串行化同一幂等标识的并发提交。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT ticket_hash FROM t_kb_registration_submission_claim "
            + "WHERE submission_id = #{submissionId} LIMIT 1 FOR UPDATE")
    String selectTicketHashForUpdate(@Param("submissionId") String submissionId);
}
