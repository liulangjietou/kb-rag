package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Knowledge base application: the unit an API key calls and a version snapshot belongs to,
 * requirement section 4.7.
 *
 * <p>The row carries no configuration at all. Every parameter lives on the versions, the editable one
 * being the {@code DRAFT} version, so there is exactly one place a configuration can be read from and
 * no way for an application level default to diverge from what a released snapshot froze.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_app")
public class App extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("app_id")
    private String appId;

    /** Owning tenant business id, defaulted to the built in tenant by the V17 migration. */
    @TableField("tenant_id")
    private String tenantId;

    /** Display name. */
    @TableField("name")
    private String name;

    /** Free text description. */
    @TableField("description")
    private String description;
}
