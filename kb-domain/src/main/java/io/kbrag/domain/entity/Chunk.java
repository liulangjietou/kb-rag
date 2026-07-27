package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.EmbeddingStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Chunk fact source. MySQL is authoritative, the search engines are derived projections.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_chunk")
public class Chunk extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier, also the primary key inside the search engines. */
    @TableField("chunk_id")
    private String chunkId;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Owning document business id. */
    @TableField("doc_id")
    private String docId;

    /** Owning document version business id, mandatory for retrieval version isolation. */
    @TableField("document_version_id")
    private String documentVersionId;

    /** Chunk text. */
    @TableField("content")
    private String content;

    /** SHA-256 of the normalised text, used for cross version annotation inheritance. */
    @TableField("chunk_text_hash")
    private String chunkTextHash;

    /** Parent chunk business id when parent child splitting is enabled. */
    @TableField("parent_id")
    private String parentId;

    /**
     * Start offset of this child's text inside its parent's {@code content}, requirement section 4.5.
     *
     * <p>{@code null} means "unknown", which is the only safe default: the retrieval side cuts a disabled
     * child out of the parent text with these offsets, so a stale pair would remove the wrong sentence
     * while a missing one merely returns the whole parent. Every write path that can move the text - a
     * child edit, a merge or split, an edit of the parent itself - clears it.
     */
    @TableField("parent_start_offset")
    private Integer parentStartOffset;

    /** Exclusive end offset of this child's text inside its parent's {@code content}. */
    @TableField("parent_end_offset")
    private Integer parentEndOffset;

    /** Zero based order inside the document version. */
    @TableField("seq")
    private Integer seq;

    /** Content nature. */
    @TableField("chunk_type")
    private ChunkType chunkType;

    /** 0 disables the chunk for retrieval. */
    @TableField("enabled")
    private Integer enabled;

    /** Embedding lifecycle state. */
    @TableField("embedding_status")
    private EmbeddingStatus embeddingStatus;

    /** Non filterable metadata kept only in MySQL, serialised as JSON. */
    @TableField("metadata")
    private String metadata;
}
