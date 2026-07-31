package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MemoryProfile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_memory_profile.
 *
 * <p>Deletions are physical, same shape as the web credential removal: a soft deleted row would
 * hold {@code uk_rule_user} hostage and the extraction upsert could never rebuild the entity's
 * profile.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MemoryProfileMapper extends BaseMapper<MemoryProfile> {

    /**
     * Physically removes one profile row.
     */
    @Delete("DELETE FROM t_kb_memory_profile WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);

    /**
     * Physically removes every profile of one rule, the cleanup of a rule deletion.
     */
    @Delete("DELETE FROM t_kb_memory_profile WHERE rule_id = #{ruleId}")
    int hardDeleteByRuleId(@Param("ruleId") String ruleId);

    /**
     * Physically removes every profile of one library, the cleanup of a library deletion.
     */
    @Delete("DELETE FROM t_kb_memory_profile WHERE library_id = #{libraryId}")
    int hardDeleteByLibraryId(@Param("libraryId") String libraryId);
}
