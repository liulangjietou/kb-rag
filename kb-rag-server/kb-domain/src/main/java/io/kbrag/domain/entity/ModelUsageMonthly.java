package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One tenant-month's atomic quota counter.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_model_usage_monthly")
public class ModelUsageMonthly extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableField("tenant_id")
    private String tenantId;
    @TableField("usage_month")
    private String usageMonth;
    @TableField("used_tokens")
    private Long usedTokens;
    @TableField("reserved_tokens")
    private Long reservedTokens;
}
