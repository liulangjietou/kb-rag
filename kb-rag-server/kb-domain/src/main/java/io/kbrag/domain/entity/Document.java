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

    /** Value of {@code trashed} inside the recycle bin. */
    private static final int TRASHED = 1;

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

    /**
     * 是否已被移入回收站。
     *
     * <p>放在实体上而不是各个 service 里各写一份：这个判断同时被治理、批量删除、外部数据源与网页
     * 同步用到，它是"文档处于什么状态"的领域知识，不是某个服务的私事。M11 之前的历史行该列为
     * {@code null}，一律视为不在回收站。
     *
     * @return true 表示文档在回收站中
     */
    public boolean inTrash() {
        return trashed != null && trashed == TRASHED;
    }
}
