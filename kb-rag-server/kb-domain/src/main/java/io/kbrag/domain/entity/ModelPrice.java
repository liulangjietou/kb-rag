package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Operator-maintained price of one provider/capability/model tuple.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_model_price")
public class ModelPrice extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String provider;
    private String capability;
    private String model;
    private String currency;

    @TableField("input_price_micros")
    private Long inputPriceMicros;

    @TableField("output_price_micros")
    private Long outputPriceMicros;

    private Integer enabled;
}
