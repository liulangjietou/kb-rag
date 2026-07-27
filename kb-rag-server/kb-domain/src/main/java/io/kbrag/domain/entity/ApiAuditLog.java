package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.TargetStage;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One outbound API call, requirement section 4.8 "audit log".
 *
 * <p>Rejected calls are recorded too - an authorisation failure is exactly the event an audit trail
 * exists for - which is why {@link #appVersionId} and {@link #targetStage} are nullable while
 * {@link #keyId} is not.
 *
 * <p>{@link #queryDigest} is masked by the cleaning rules and then truncated. An audit trail that stored
 * the raw query would quietly become a second, unmasked copy of the personal data the ingestion path
 * takes care to mask.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "queryDigest")
@TableName("t_kb_api_audit_log")
public class ApiAuditLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed by the console query API. */
    @TableField("audit_log_id")
    private String auditLogId;

    /** Calling API key business id, never key material. */
    @TableField("key_id")
    private String keyId;

    /** Application called, {@code null} when the request never named a valid one. */
    @TableField("app_id")
    private String appId;

    /** Application version that served the call, {@code null} when the call was rejected. */
    @TableField("app_version_id")
    private String appVersionId;

    /** Version stage served, {@code null} when the call was rejected. */
    @TableField("target_stage")
    private TargetStage targetStage;

    /** Endpoint literal, {@code search} or {@code chat}. */
    @TableField("endpoint")
    private String endpoint;

    /** Masked and truncated query. */
    @TableField("query_digest")
    private String queryDigest;

    /** JSON array of the document ids the returned nodes came from. */
    @TableField("hit_doc_ids")
    private String hitDocIds;

    /** Server side duration in milliseconds. */
    @TableField("latency_ms")
    private Integer latencyMs;

    /** JSON array of degradation markers of this call. */
    @TableField("degraded")
    private String degraded;

    /** JSON array of the request level override keys applied, requirement section 5. */
    @TableField("override_keys")
    private String overrideKeys;

    /** Business error code when the call was rejected, {@code null} on success. */
    @TableField("error_code")
    private String errorCode;

    /** Correlation id of the call. */
    @TableField("request_id")
    private String requestId;
}
