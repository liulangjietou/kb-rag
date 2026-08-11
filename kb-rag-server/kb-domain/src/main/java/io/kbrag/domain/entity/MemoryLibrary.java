package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * A memory library, the M19 contract: the isolation unit every memory key is bound to.
 *
 * <p>One library holds the rules, memory nodes and profiles of one consuming application. Isolation
 * between applications is the library boundary, isolation inside a library is the {@code user_id}
 * of each node - both are query predicates, never conventions.
 *
 * <p>Above those two sits the tenant, and this is the only memory table carrying it: the root
 * aggregate of the memory domain. Rules, nodes, profiles and keys reach their tenant through their
 * library, which is why fencing this one table isolates all six.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_library")
public class MemoryLibrary extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("library_id")
    private String libraryId;

    /** Owning tenant business id, defaulted to the built in tenant by the V21 migration. */
    @TableField("tenant_id")
    private String tenantId;

    /** Display name; uniqueness among live rows is guarded by the service layer. */
    @TableField("name")
    private String name;

    /** Free form description, may double as guidance text for the calling agent. */
    @TableField("description")
    private String description;
}
