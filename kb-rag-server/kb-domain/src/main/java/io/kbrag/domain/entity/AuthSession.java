package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 控制台会话的一条键值记录，Sa-Token 在 {@code cache.provider=local} 下的持久化形态。
 *
 * <p><b>刻意不继承 {@link BaseEntity}。</b> 会话是易失数据，不是业务实体：逻辑删除会让每次登出只把行
 * 标记掉、表只增不减，而乐观锁对"最后一次续期即有效"的语义没有意义。这两列的缺席是结论，不是遗漏。
 *
 * <p>值不做摘要处理，这一点和已经删除的 {@code AuthToken} 不同，也不是倒退：老表存的是令牌本身，摘要
 * 保证拖库拿不到可重放的凭据；这里的键才是令牌派生的，值装的是会话内容。键的不可预测性由 Sa-Token 的
 * 令牌生成负责，值则必须能原样读回来，摘要化会让会话无法反序列化。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(exclude = "sessionValue")
@TableName("t_kb_auth_session")
public class AuthSession {

    /** 自增代理主键，不对外暴露。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** Sa-Token 的存储键，全局唯一。 */
    @TableField("session_key")
    private String sessionKey;

    /** Sa-Token 的存储值，会话对象序列化后为 JSON。 */
    @TableField("session_value")
    private String sessionValue;

    /** 绝对过期时刻；{@code null} 表示永不过期。 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 行创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 行更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
