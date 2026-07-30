package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One successful management console write, the "who did what" record of the M16 contract section 7.
 *
 * <p>{@link #username} is stored redundantly next to {@link #userId} so a record stays readable after
 * the account is deleted - an audit trail that needs a join against a row that may be gone answers
 * "who" with a foreign key violation. {@link #detail} carries business ids and summary fields only,
 * never the request body: passwords and document content pass through the write endpoints this table
 * observes.
 *
 * <p>Deliberately outside the tenant fence: the list endpoint pins its own tenant condition, but the
 * table itself keeps global rows so cross tenant incidents remain traceable from the database.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_operation_audit")
public class OperationAudit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed by the console. */
    @TableField("audit_id")
    private String auditId;

    /** Tenant of the operator at the time of the operation. */
    @TableField("tenant_id")
    private String tenantId;

    /** Operator user business id. */
    @TableField("user_id")
    private String userId;

    /** Operator login name, redundant so the row outlives the account. */
    @TableField("username")
    private String username;

    /** Module the operation belongs to, such as {@code KB} or {@code USER}. */
    @TableField("module")
    private String module;

    /** Action performed, such as {@code CREATE} or {@code DELETE}. */
    @TableField("action")
    private String action;

    /** Kind of object acted on, such as {@code KNOWLEDGE_BASE} or {@code ROLE}. */
    @TableField("target_type")
    private String targetType;

    /** Business id of the object acted on, {@code null} for batch operations. */
    @TableField("target_id")
    private String targetId;

    /** JSON of business ids and summary fields, never the request body. */
    @TableField("detail")
    private String detail;

    /** Source address of the request. */
    @TableField("client_ip")
    private String clientIp;

    /** Correlation id, links the row to logs. */
    @TableField("request_id")
    private String requestId;
}
