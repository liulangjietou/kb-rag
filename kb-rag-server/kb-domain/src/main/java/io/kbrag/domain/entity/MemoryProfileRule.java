package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.MemoryExtractVersion;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * A user profile rule, the M19 contract: which structured attributes are extracted per memory
 * entity.
 *
 * <p>The field list is stored as one JSON column rather than a child table: fields are only ever
 * edited and read as a whole with their rule, and no query path selects by field.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_profile_rule")
public class MemoryProfileRule extends BaseEntity {

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

    /** Extraction pipeline variant. */
    @TableField("extract_version")
    private MemoryExtractVersion extractVersion;

    /** Field definitions as a JSON array of {@code {name, description, initial_value}}, at most 50. */
    @TableField("fields")
    private String fields;
}
