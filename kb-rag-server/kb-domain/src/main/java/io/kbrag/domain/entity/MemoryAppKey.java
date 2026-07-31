package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.MemoryAppKeyStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A memory key row, the M19 contract: the credential a consuming agent presents to the memory open
 * API.
 *
 * <p>Same decision as {@link ApiKey}: the plaintext exists only in the creation response, the
 * SHA-256 digest is what authentication looks up and the display form is what a list page may show.
 * Each key is bound to exactly one library, which is what makes application isolation a join
 * condition instead of a convention.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_app_key")
public class MemoryAppKey extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API, never the key material itself. */
    @TableField("key_id")
    private String keyId;

    /** Library the key can read and write - the only one. */
    @TableField("library_id")
    private String libraryId;

    /** Purpose note, e.g. the name of the consuming agent. */
    @TableField("name")
    private String name;

    /** SHA-256 hexadecimal digest of the plaintext key; authentication looks rows up by it. */
    @TableField("key_hash")
    private String keyHash;

    /** Display form (prefix plus last four), safe for list pages. */
    @TableField("key_prefix")
    private String keyPrefix;

    /** Lifecycle state; disabling takes effect on the next request. */
    @TableField("status")
    private MemoryAppKeyStatus status;

    /** Requests per second allowed through the token bucket. */
    @TableField("qps_limit")
    private Integer qpsLimit;

    /** Last successful authentication, updated asynchronously, {@code null} when never used. */
    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;
}
