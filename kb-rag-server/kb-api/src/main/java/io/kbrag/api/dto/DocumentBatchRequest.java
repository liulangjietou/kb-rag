package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Scope of a batch document operation.
 *
 * <p>doc_ids 在这里是必填的，和 {@link RebuildRequest}、{@link ConfirmDocumentsRequest} 的"缺省即全量"
 * 相反。那两个操作缺省成全量是安全的——重建与确认都可以重复执行；而批量删除完全由控制台的勾选
 * 驱动，一个空列表只可能是前端的疏漏，把它解释成"对整个知识库执行"是个会造成损失的默认值。
 *
 * @param docIds documents the operation applies to
 *
 * @author owlzhangfq@gmail.com
 */
public record DocumentBatchRequest(
        @NotEmpty(message = "doc_ids is required")
        @JsonProperty("doc_ids") List<String> docIds) {
}
