package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ExtSourceItemStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One object of an external source and the outcome of its last sync, the M14 contract section 1.
 *
 * <p>A row is created the first time a listing surfaces the key and updated on every later visit,
 * so the item table is the operator's per-object view of what a sync pass actually did.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_ext_source_item")
public class ExtSourceItem extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Owning external source business id. */
    @TableField("source_id")
    private String sourceId;

    /** Object key inside the bucket. */
    @TableField("object_key")
    private String objectKey;

    /** SHA-256 of the object key; the equality key a VARCHAR(1024) column cannot be. */
    @TableField("object_key_hash")
    private String objectKeyHash;

    /** Etag of the last ingested object body, the unchanged check of an incremental sync. */
    @TableField("etag")
    private String etag;

    /** Document the object feeds, {@code null} until the first successful ingest. */
    @TableField("doc_id")
    private String docId;

    /** Outcome of the last sync visit of this object. */
    @TableField("last_status")
    private ExtSourceItemStatus lastStatus;

    /** Why the last visit failed or was skipped, {@code null} on success. */
    @TableField("last_error")
    private String lastError;

    /** When this object was last visited by a sync. */
    @TableField("last_sync_at")
    private LocalDateTime lastSyncAt;
}
