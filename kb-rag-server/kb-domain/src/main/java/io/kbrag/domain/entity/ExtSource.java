package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ExtSourceSyncStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A registered external object store source, the M14 contract section 1: connection details of one
 * S3/OSS compatible bucket, the sync switch and the outcome of the last sync pass.
 *
 * <p>The binding to the documents it produced is deliberately weak, the same shape as
 * {@link WebSource}: removing the registration leaves the documents alone, and trashing a document
 * leaves the registration alone - the two lifecycles meet only inside a sync pass.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "secretKey")
@TableName("t_kb_ext_source")
public class ExtSource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("source_id")
    private String sourceId;

    /** Knowledge base the fetched objects land in. */
    @TableField("kb_id")
    private String kbId;

    /** Connector type routing key, {@code s3} in this milestone. */
    @TableField("source_type")
    private String sourceType;

    /** Operator facing display name, unique per knowledge base. */
    @TableField("name")
    private String name;

    /** Service endpoint of the object store. */
    @TableField("endpoint")
    private String endpoint;

    /** Optional region hint of the object store. */
    @TableField("region")
    private String region;

    /** Bucket the scan lists. */
    @TableField("bucket")
    private String bucket;

    /** Optional key prefix narrowing the scan. */
    @TableField("prefix")
    private String prefix;

    /** Access key of the bucket credentials. */
    @TableField("access_key")
    private String accessKey;

    /** Secret key of the bucket credentials; stored in clear, never returned by the read API. */
    @TableField("secret_key")
    private String secretKey;

    /** {@code 1} includes the source in the scheduled sync pass. */
    @TableField("sync_enabled")
    private Integer syncEnabled;

    /** When the last sync attempt ran, success or not. */
    @TableField("last_sync_at")
    private LocalDateTime lastSyncAt;

    /** Outcome of the last sync pass. */
    @TableField("last_sync_status")
    private ExtSourceSyncStatus lastSyncStatus;

    /** Why the last sync failed or was partial, {@code null} on success. */
    @TableField("last_error")
    private String lastError;
}
