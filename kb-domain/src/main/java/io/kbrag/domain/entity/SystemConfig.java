package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Global system settings, layer two of the five layer configuration model.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_system_config")
public class SystemConfig extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Configuration key, unique. */
    @TableField("config_key")
    private String configKey;

    /** Configuration value, JSON for structured entries. */
    @TableField("config_value")
    private String configValue;

    /** Human readable description shown in the console. */
    @TableField("description")
    private String description;
}
