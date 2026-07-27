package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.AuthToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_auth_token.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AuthTokenMapper extends BaseMapper<AuthToken> {
}
