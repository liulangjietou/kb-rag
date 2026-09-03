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
 * 单点登录流程中的一次性 state。
 *
 * <p><b>只存摘要</b>，和 API Key 的处理一致：state 的明文只存在于浏览器的重定向链路里，因此即使数据库
 * 被拖走，也无法拿去伪造一次回调。
 *
 * <p>刻意不继承 {@link BaseEntity}：state 一旦被消费就该真的消失，逻辑删除会把"一次性"变成"标记过的
 * 一次性"，而乐观锁对一张只有插入和删除的表没有意义。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(exclude = "stateHash")
@TableName("t_kb_sso_state")
public class SsoState {

    /** 自增代理主键，不对外暴露。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 一次性 state 的 SHA-256 摘要，唯一的存储形式。 */
    @TableField("state_hash")
    private String stateHash;

    /** 回调时原样取回的流程上下文。 */
    @TableField("payload")
    private String payload;

    /** 绝对过期时刻。 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 行创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
