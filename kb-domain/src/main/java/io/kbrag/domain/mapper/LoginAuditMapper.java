package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.LoginAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_login_audit.
 */
@Mapper
public interface LoginAuditMapper extends BaseMapper<LoginAudit> {
}
