package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.IndexSyncStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Synchronization state per chunk and per physical index.
 *
 * <p>Modelled on the physical index rather than on the engine so a rebuild window, where the same
 * chunk targets both the old and the new index, is representable.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_chunk_index_sync")
public class ChunkIndexSync extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Chunk business id. */
    @TableField("chunk_id")
    private String chunkId;

    /** Physical index or collection name, part of the unique key. */
    @TableField("physical_index_name")
    private String physicalIndexName;

    /** Engine code, {@code es} or {@code milvus}. */
    @TableField("engine")
    private String engine;

    /** Synchronization state. */
    @TableField("status")
    private IndexSyncStatus status;

    /** Number of write retries already performed. */
    @TableField("retry_count")
    private Integer retryCount;
}
