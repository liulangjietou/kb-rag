package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * A user profile, the M19 contract: one row per memory entity per profile rule.
 *
 * <p>Extraction upserts against the {@code uk_rule_user} key, merging newly extracted attributes
 * over the stored ones. Attributes the model has not filled yet are absent from the JSON and fall
 * back to the rule's initial values at read time - absence and emptiness stay distinguishable.
 *
 * <p>Rows are removed physically, never soft deleted: a soft deleted row would hold
 * {@code uk_rule_user} hostage and block the entity's profile from ever being rebuilt.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_memory_profile")
public class MemoryProfile extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Library the profile belongs to. */
    @TableField("library_id")
    private String libraryId;

    /** Profile rule the attributes were extracted under. */
    @TableField("rule_id")
    private String ruleId;

    /** Memory entity id chosen by the caller. */
    @TableField("user_id")
    private String userId;

    /** Extracted attributes as a JSON object of {@code {fieldName: value}}. */
    @TableField("attributes")
    private String attributes;
}
