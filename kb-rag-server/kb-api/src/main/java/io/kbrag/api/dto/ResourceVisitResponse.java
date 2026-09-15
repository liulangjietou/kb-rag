package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.workspace.ResourceVisitService;
import io.kbrag.domain.enums.ResourceVisitKind;

/** 最近访问的最小视图，资源路径由前端按已知种类构造。 */
public record ResourceVisitResponse(
        @JsonProperty("resource_type") ResourceVisitKind kind,
        @JsonProperty("resource_id") String resourceId,
        String name,
        @JsonProperty("visited_at") String visitedAt) {

    /** 使用数据库中保留的访问时间，不能以资源更新时间替代。 */
    public static ResourceVisitResponse from(ResourceVisitService.RecentVisit visit) {
        return new ResourceVisitResponse(visit.kind(), visit.resourceId(), visit.name(), visit.visitedAt().toString());
    }
}
