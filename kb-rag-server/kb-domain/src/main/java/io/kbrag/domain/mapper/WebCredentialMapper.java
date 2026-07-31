package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.WebCredential;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_web_credential.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface WebCredentialMapper extends BaseMapper<WebCredential> {

    /**
     * Physically removes one credential row.
     *
     * <p>Same shape as the web source removal: a soft-deleted row would hold {@code uk_host}
     * hostage and make the host uncredentialable forever, and a deleted secret should be gone,
     * not flagged.
     */
    @Delete("DELETE FROM t_kb_web_credential WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
