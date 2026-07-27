package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Persisted console session token, the durable half of the token store.
 *
 * <p><b>Only the digest is stored</b>, mirroring {@link ApiKey}: the plaintext token lives in the
 * browser and is carried in the {@code Authorization} header, so a database dump cannot be replayed
 * against the console.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "tokenHash")
@TableName("t_kb_auth_token")
public class AuthToken extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** SHA-256 digest of the opaque bearer token, the only stored form. */
    @TableField("token_hash")
    private String tokenHash;

    /** Console account the session belongs to. */
    @TableField("username")
    private String username;

    /** Absolute expiry, issued time plus the configured TTL. */
    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
