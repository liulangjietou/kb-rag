package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ApiKeyStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Open API credential, requirement section 4.8.
 *
 * <p><b>Only the digest is stored.</b> The plaintext is shown once at creation and is never recoverable,
 * so a database dump cannot be replayed against the open API and no log, audit row or rate limit counter
 * ever holds key material - they all reference {@link #keyId}.
 *
 * <p>{@link #appScope} is what makes a key distributable: a single administrator account is not a single
 * calling party, so a key handed to an external agent can be restricted to the applications it is meant
 * to reach.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "keyHash")
@TableName("t_kb_api_key")
public class ApiKey extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API and recorded in the audit trail. */
    @TableField("key_id")
    private String keyId;

    /** Owning tenant business id, defaulted to the built in tenant by the V17 migration. */
    @TableField("tenant_id")
    private String tenantId;

    /** Display name of the caller this key was issued to. */
    @TableField("name")
    private String name;

    /** SHA-256 digest of the plaintext key, the only stored form. */
    @TableField("key_hash")
    private String keyHash;

    /** Display only form: leading segment plus the last four characters. */
    @TableField("prefix")
    private String prefix;

    /** Lifecycle state. */
    @TableField("status")
    private ApiKeyStatus status;

    /** Token bucket rate of this key in requests per second. */
    @TableField("qps_limit")
    private Integer qpsLimit;

    /** JSON array of allowed app ids; {@code null} authorises every application. */
    @TableField("app_scope")
    private String appScope;

    /** Last successful authentication, refreshed outside the request path. */
    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;
}
