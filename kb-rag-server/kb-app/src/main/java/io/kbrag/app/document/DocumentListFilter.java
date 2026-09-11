package io.kbrag.app.document;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.PublishStatus;

import java.time.LocalDateTime;

/** 文档列表的已校验筛选条件；时间区间包含起点与终点。 */
public record DocumentListFilter(
        String keyword,
        ProcessStatus processStatus,
        PublishStatus publishStatus,
        Source source,
        LocalDateTime updatedFrom,
        LocalDateTime updatedTo) {

    private static final int MAX_KEYWORD_LENGTH = 200;

    /** 入口创建条件时一次完成规范化及区间校验。 */
    public DocumentListFilter {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw BizException.invalidParam("文档关键词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw BizException.invalidParam("更新时间起点不能晚于终点");
        }
    }

    /** 由保留的接入关联识别来源，UPLOAD 表示上传或没有关联记录的历史文档。 */
    public enum Source {
        UPLOAD, WEB, EXTERNAL, CHAT
    }
}
