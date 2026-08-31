package io.kbrag.app.auth;

import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.enums.LoginResult;
import io.kbrag.domain.mapper.LoginAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败审计写入边界。
 *
 * <p>认证失败会以异常结束外层登录事务，因此失败记录必须在独立事务中提交。该职责放在
 * 独立 Spring Bean 中，确保 {@link Propagation#REQUIRES_NEW} 能经过事务代理生效。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class LoginFailureAuditService {

    private static final int FAILURE_FLAG = 0;

    private final LoginAuditMapper loginAuditMapper;

    /**
     * 在独立事务中保存一次失败登录。
     *
     * @param username 标准化后的用户名
     * @param ip       已解析的来源地址
     * @param reason   精确的失败原因
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String ip, LoginResult reason) {
        LoginAudit record = new LoginAudit();
        record.setUsername(username);
        record.setIp(ip);
        record.setSuccess(FAILURE_FLAG);
        record.setReason(reason);
        loginAuditMapper.insert(record);
    }
}
