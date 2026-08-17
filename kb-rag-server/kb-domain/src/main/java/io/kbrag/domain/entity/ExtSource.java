package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ExtSourceSyncStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A registered external source, the M14/M23 contract: connection details of an S3/OSS bucket or a
 * Confluence Cloud space, the sync switch and the outcome of the last sync pass.
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

    /** Connector type routing key: {@code s3} or {@code confluence}. */
    @TableField("source_type")
    private String sourceType;

    /** Operator facing display name, unique per knowledge base. */
    @TableField("name")
    private String name;

    /** Remote service endpoint. */
    @TableField("endpoint")
    private String endpoint;

    /** Optional connector-specific region hint. */
    @TableField("region")
    private String region;

    /** Connector collection identifier: bucket name or Confluence space key. */
    @TableField("bucket")
    private String bucket;

    /** Optional connector-specific listing prefix. */
    @TableField("prefix")
    private String prefix;

    /** Public credential half: object-store access key or Atlassian account email. */
    @TableField("access_key")
    private String accessKey;

    /** Secret credential half; stored in clear, never returned by the read API. */
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
