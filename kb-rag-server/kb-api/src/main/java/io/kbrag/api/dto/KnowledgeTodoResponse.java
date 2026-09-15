package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.workspace.KnowledgeTodoService;
import io.kbrag.domain.enums.KnowledgeTodoKind;

/** 首页待办只返回当前知识库名称、数量与动作权限。 */
public record KnowledgeTodoResponse(@JsonProperty("kb_id") String kbId,
                                    @JsonProperty("kb_name") String kbName,
                                    KnowledgeTodoKind kind, long total,
                                    @JsonProperty("can_process") boolean canProcess) {
    /** 把服务结果映射为稳定的接口字段。 */
    public static KnowledgeTodoResponse from(KnowledgeTodoService.Todo item) {
        return new KnowledgeTodoResponse(item.kbId(), item.kbName(), item.kind(), item.total(), item.canProcess());
    }
}
