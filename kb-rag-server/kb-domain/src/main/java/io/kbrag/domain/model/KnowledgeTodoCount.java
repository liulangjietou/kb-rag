package io.kbrag.domain.model;

import io.kbrag.domain.enums.KnowledgeTodoKind;
import lombok.Getter;
import lombok.Setter;

/** 按已授权知识库和事项种类聚合，不承载文档正文或来源连接配置。 */
@Getter
@Setter
public class KnowledgeTodoCount {
    private String kbId;
    private KnowledgeTodoKind kind;
    private long total;
}
