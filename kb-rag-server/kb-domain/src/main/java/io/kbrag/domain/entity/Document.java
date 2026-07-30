package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.DocVisibility;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.PublishStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

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

    /** Document level visibility, INHERIT for every row until an operator restricts it (M16). */
    @TableField("visibility")
    private DocVisibility visibility;

    /** Editorial state, orthogonal to the processing state (M11 governance). */
    @TableField("publish_status")
    private PublishStatus publishStatus;

    /** Latest rejection reason, cleared on approval. */
    @TableField("review_note")
    private String reviewNote;

    /** Instant the document becomes retrievable, {@code null} for no lower bound. */
    @TableField("effective_at")
    private LocalDateTime effectiveAt;

    /** Instant the document stops being retrievable, {@code null} for no upper bound. */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** {@code 1} while the document sits in the recycle bin, out of retrieval but restorable. */
    @TableField("trashed")
    private Integer trashed;

    /** When the document entered the recycle bin, drives the retention purge. */
    @TableField("trashed_at")
    private LocalDateTime trashedAt;

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
