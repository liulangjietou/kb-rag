package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.MemoryExtractVersion;
import io.kbrag.domain.enums.MemoryInstructionType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * A memory fragment rule, the M19 contract: how conversations of one library are distilled into
 * memory nodes.
 *
 * <p>Expiry is stored as a day count instead of an enum literal so the write path can compute
 * {@code expire_at} with plain arithmetic; {@code null} means the memory never expires and the
 * whole semantic closes over one column.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_fragment_rule")
public class MemoryFragmentRule extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("rule_id")
    private String ruleId;

    /** Library the rule belongs to. */
    @TableField("library_id")
    private String libraryId;

    /** Display name, unique inside the library by service layer check. */
    @TableField("name")
    private String name;

    /** Whether the built in extraction instruction or the custom one is used. */
    @TableField("instruction_type")
    private MemoryInstructionType instructionType;

    /** Custom extraction instruction, mandatory when the type is CUSTOM, {@code null} otherwise. */
    @TableField("instruction")
    private String instruction;

    /** {@code 1} lets extraction merge and update the entity's old memories instead of only appending. */
    @TableField("auto_update")
    private Integer autoUpdate;

    /** Lifetime of extracted memories in days (7/30/180), {@code null} for never expiring. */
    @TableField("expire_days")
    private Integer expireDays;

    /** Extraction pipeline variant. */
    @TableField("extract_version")
    private MemoryExtractVersion extractVersion;

    /** {@code 1} marks the seeded default rule of the library: editable, never deletable. */
    @TableField("builtin")
    private Integer builtin;
}
