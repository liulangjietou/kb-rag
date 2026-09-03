package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.SsoState;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * t_kb_sso_state 的数据访问。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SsoStateMapper extends BaseMapper<SsoState> {

    /**
     * 回收已过期的 state。
     *
     * <p>正常流程里 state 在回调时就被消费掉了，留在表里的都是没走完的流程——用户关掉了 IdP 的登录页、
     * 或者干脆没登录成功。这些行没有读者，只是占地方。
     *
     * @return 清理掉的行数
     */
    @Delete("DELETE FROM t_kb_sso_state WHERE expires_at <= NOW()")
    int purgeExpired();
}
