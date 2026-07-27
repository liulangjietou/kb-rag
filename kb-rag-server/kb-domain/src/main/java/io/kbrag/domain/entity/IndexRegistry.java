package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.IndexRegistryStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Registry of physical indices and their aliases, single source of truth for alias switching.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_index_registry")
public class IndexRegistry extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Engine code, {@code es} or {@code qdrant}. */
    @TableField("engine")
    private String engine;

    /** Three segment physical name, unique across the deployment. */
    @TableField("physical_index_name")
    private String physicalIndexName;

    /** Alias all reads and writes go through. */
    @TableField("alias_name")
    private String aliasName;

    /** 1 when the alias currently points at this physical index. */
    @TableField("is_current")
    private Integer isCurrent;

    /** Embedding provider name, {@code none} in zero key mode. */
    @TableField("embedding_provider")
    private String embeddingProvider;

    /** Embedding model name, {@code none} in zero key mode. */
    @TableField("embedding_model")
    private String embeddingModel;

    /** Embedding version segment used in the physical name. */
    @TableField("embedding_version")
    private String embeddingVersion;

    /** Snapshot segment, fixed to {@code v1} in M1. */
    @TableField("snapshot_version")
    private String snapshotVersion;

    /** Engine schema version, checked at startup to detect incompatible field sets. */
    @TableField("schema_version")
    private String schemaVersion;

    /** Registry lifecycle state. */
    @TableField("status")
    private IndexRegistryStatus status;

    /** Task that created this physical index, {@code null} for the initial build. */
    @TableField("task_id")
    private String taskId;
}
