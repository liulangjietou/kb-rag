package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.MemoryNodeSource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A memory node, the M19 contract: one remembered fact about one memory entity.
 *
 * <p>MySQL is the source of truth; a search copy of every live, unexpired node is kept in
 * Elasticsearch for BM25 and vector recall. Expiry is a timestamp computed at write time from the
 * rule's day count, so "expired" is a comparison rather than a nightly job.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_node")
public class MemoryNode extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("node_id")
    private String nodeId;

    /** Library the node belongs to. */
    @TableField("library_id")
    private String libraryId;

    /** Fragment rule that produced the node. */
    @TableField("rule_id")
    private String ruleId;

    /** Memory entity id chosen by the caller; entities never see each other's nodes. */
    @TableField("user_id")
    private String userId;

    /** Remembered content. */
    @TableField("content")
    private String content;

    /** Whether the LLM extracted the node or the caller wrote it verbatim. */
    @TableField("source")
    private MemoryNodeSource source;

    /** Caller supplied metadata JSON, stored and returned verbatim, {@code null} when absent. */
    @TableField("meta_data")
    private String metaData;

    /** Moment the node stops being retrievable, {@code null} for never. */
    @TableField("expire_at")
    private LocalDateTime expireAt;
}
