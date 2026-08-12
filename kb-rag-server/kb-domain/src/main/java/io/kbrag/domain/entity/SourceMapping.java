package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.SourceMappingType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One chat import mapping profile: the YAML the parser needs in order to read an export format.
 *
 * <p>MySQL is the fact source and the profile body travels to the parser with every parse request, which
 * is what turns "onboard a new export format" into a console edit instead of a parser deployment. The
 * parser keeps its own copies of the built-in files, but only as its default and as the seed of this
 * table.
 *
 * <p><b>A built-in row is read only.</b> It is the template the next release recalibrates against a real
 * export sample, so an in place edit would be silently reverted by that recalibration; the console copies
 * it into a custom row instead, which is a row nobody upstream will ever overwrite.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "profileYaml")
@TableName("t_kb_source_mapping")
public class SourceMapping extends BaseEntity {

    /** Value of {@link #isBuiltin} marking a seeded template. */
    public static final int BUILTIN = 1;

    /** Value of {@link #isBuiltin} marking an operator created row. */
    public static final int CUSTOM = 0;

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("mapping_id")
    private String mappingId;

    /** Owning tenant business id, defaulted to the built in tenant by the V23 migration. */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * Profile name, unique inside one tenant.
     *
     * <p>Also the value the {@code mapping_profile} import parameter carried before this table existed, so
     * an import script written against a built-in name keeps working without translation.
     *
     * <p>Unique per tenant rather than globally since V23: the built-in profiles are copied into every
     * tenant, so a name like {@code memotrace} exists once per tenant by design.
     */
    @TableField("name")
    private String name;

    /** Export format this profile targets. */
    @TableField("source_type")
    private SourceMappingType sourceType;

    /**
     * Full YAML body forwarded to the parser.
     *
     * <p>Carries its own explanation in its comment header, which is why the row has no description
     * column: the console edits the body in a text area, so the comments are already in front of the
     * operator and a second field would only be able to disagree with them.
     */
    @TableField("profile_yaml")
    private String profileYaml;

    /** {@code 1} for a seeded template, which can be copied but neither edited nor deleted. */
    @TableField("is_builtin")
    private Integer isBuiltin;

    /**
     * Tells whether this row is a seeded template.
     *
     * @return {@code true} when the row is built in
     */
    public boolean builtin() {
        return isBuiltin != null && isBuiltin == BUILTIN;
    }
}
