package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户名邮箱与联系邮箱共享的全局身份声明。
 *
 * <p>该表故意不带租户字段：同一个邮箱不能在另一个租户再次成为登录身份。声明与用户创建、
 * 更新处在同一事务中，数据库主键是并发竞争的最终裁决者。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EmailIdentityClaimMapper {

    /**
     * 原子创建声明；已有声明时只获取该主键上的数据库锁，不改变原持有人。
     *
     * @param normalizedEmail 规范化邮箱
     * @param userId          申请持有声明的用户
     * @return MySQL 受影响行数，调用方不依赖其具体值
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO t_kb_email_identity_claim (normalized_email, owner_user_id)
            VALUES (#{normalizedEmail}, #{userId})
            ON DUPLICATE KEY UPDATE normalized_email = t_kb_email_identity_claim.normalized_email
            """)
    int reserve(@Param("normalizedEmail") String normalizedEmail,
                @Param("userId") String userId);

    /** 查询当前持有人；紧跟在 {@link #reserve(String, String)} 后调用时共享同一事务锁。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT owner_user_id
            FROM t_kb_email_identity_claim
            WHERE normalized_email = #{normalizedEmail}
            """)
    String selectOwner(@Param("normalizedEmail") String normalizedEmail);

    /** 仅释放指定用户确实持有、且已不再被用户名引用的旧联系邮箱。 */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
            DELETE FROM t_kb_email_identity_claim
            WHERE normalized_email = #{normalizedEmail}
              AND owner_user_id = #{userId}
            """)
    int releaseOwned(@Param("normalizedEmail") String normalizedEmail,
                     @Param("userId") String userId);
}
