package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ProcessStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Document master record. Holds the pointer to the currently active version.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_document")
public class Document extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("doc_id")
    private String docId;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Original upload file name. */
    @TableField("file_name")
    private String fileName;

    /** Lower case extension without the dot. */
    @TableField("file_ext")
    private String fileExt;

    /** Original file size in bytes. */
    @TableField("file_size")
    private Long fileSize;

    /** Business id of the active version, {@code null} until the first build succeeds. */
    @TableField("current_version_id")
    private String currentVersionId;

    /** Single valued processing state. */
    @TableField("process_status")
    private ProcessStatus processStatus;

    /** Set when the knowledge base configuration fingerprint no longer matches the active version. */
    @TableField("config_stale")
    private Integer configStale;

    /** Classified failure cause, cleared on a successful rerun. */
    @TableField("fail_reason")
    private String failReason;

    /**
     * Stable identity of the logical source this document stands for, {@code null} for plain uploads.
     *
     * <p>A chat session is a logical document rather than a file: importing the same session twice has
     * to produce a new version of one document, not two documents. The file name cannot carry that
     * identity because it is a display value the user may see repeated across sessions.
     */
    @TableField("source_key")
    private String sourceKey;
}
