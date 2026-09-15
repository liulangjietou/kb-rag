package io.kbrag.domain.model;

import java.time.LocalDateTime;

/** 持久引用只保存稳定标识与实际检索片段，不保存预签 URL 或未经筛选的调试元数据。 */
public record EmployeeCitation(String docId, String documentVersionId, String chunkId, String kbId,
                               String fileName, String documentVersion, LocalDateTime documentUpdatedAt,
                               LocalDateTime versionCreatedAt, String chunkTitle, Integer pageNo,
                               Integer chunkOrdinal, String content, boolean inherited) {

    /** 历史上下文带来的来源依赖也要随新回答保存，防止多轮问答绕过后续撤权。 */
    public EmployeeCitation asInherited() {
        return new EmployeeCitation(docId, documentVersionId, chunkId, kbId, fileName, documentVersion,
                documentUpdatedAt, versionCreatedAt, chunkTitle, pageNo, chunkOrdinal, content, true);
    }
}
