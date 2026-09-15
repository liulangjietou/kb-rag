package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/** 角色可使用的一个应用；所属租户通过角色和应用根资源共同校验。 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_role_app")
public class RoleAppScope extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableField("role_id")
    private String roleId;

    @TableField("app_id")
    private String appId;
}
