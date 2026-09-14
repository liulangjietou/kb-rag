package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ResourceVisitKind;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 每位用户对每个资源只保留最后一次访问，不保存资源正文、路径参数或点击流水。 */
@Getter
@Setter
@TableName("t_kb_resource_visit")
public class ResourceVisit extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @TableField("tenant_id")
    private String tenantId;
    @TableField("user_id")
    private String userId;
    @TableField("resource_type")
    private ResourceVisitKind resourceType;
    @TableField("resource_id")
    private String resourceId;
    @TableField("visited_at")
    private LocalDateTime visitedAt;
}
