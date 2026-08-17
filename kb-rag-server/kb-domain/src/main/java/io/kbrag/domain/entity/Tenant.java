package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.TenantStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Tenant: the mounting point of the row level isolation on the root aggregate tables.
 *
 * <p>Only root aggregates (user, role, knowledge base, api key, eval dataset, app) carry a tenant
 * column; every subordinate resource reaches its tenant through its root. A second tenant column on a
 * child table would only be a second source of truth able to disagree with the first.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_tenant")
public class Tenant extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API, {@code tnt_} prefixed. */
    @TableField("tenant_id")
    private String tenantId;

    /** Stable code, immutable after creation; the index naming tenant segment derives from it. */
    @TableField("code")
    private String code;

    /** Display name. */
    @TableField("name")
    private String name;

    /** Lifecycle state; a disabled tenant refuses every login of its accounts. */
    @TableField("status")
    private TenantStatus status;

    /** 1 marks the tenant shipped with the product: it cannot be disabled. */
    @TableField("builtin")
    private Integer builtin;

    /** Monthly model Token quota; zero means unlimited. */
    @TableField("monthly_token_quota")
    private Long monthlyTokenQuota;

    /**
     * Tells whether the tenant is shipped with the product.
     *
     * @return {@code true} for the built in default tenant
     */
    public boolean builtin() {
        return builtin != null && builtin == 1;
    }

    /**
     * Tells whether accounts of the tenant may log in.
     *
     * @return {@code true} when enabled
     */
    public boolean enabled() {
        return status == TenantStatus.ENABLED;
    }
}
