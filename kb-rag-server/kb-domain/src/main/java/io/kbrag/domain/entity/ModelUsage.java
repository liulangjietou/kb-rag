package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Append-oriented ledger row of one upstream model request.
 *
 * <p>No prompt, answer, image or exception message is stored: accounting needs quantities and safe
 * dimensions, not customer content.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_model_usage")
public class ModelUsage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableField("usage_id")
    private String usageId;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("request_id")
    private String requestId;
    private String source;
    @TableField("source_id")
    private String sourceId;
    private String provider;
    private String capability;
    private String model;
    private String status;
    @TableField("reserved_tokens")
    private Long reservedTokens;
    @TableField("input_tokens")
    private Long inputTokens;
    @TableField("output_tokens")
    private Long outputTokens;
    @TableField("total_tokens")
    private Long totalTokens;
    private Integer estimated;
    private Integer priced;
    private String currency;
    @TableField("input_price_micros")
    private Long inputPriceMicros;
    @TableField("output_price_micros")
    private Long outputPriceMicros;
    @TableField("cost_micros")
    private Long costMicros;
    @TableField("error_type")
    private String errorType;
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
