package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_tenant.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
