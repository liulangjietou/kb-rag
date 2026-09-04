package io.kbrag.app.auth;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.LoginAudit;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.LoginAuditMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 固化凭据校验后并发账号变更必须在签发 token 前安全失败。
 *
 * @author owlzhangfq@gmail.com
 */
class LoginSuccessServiceTest {

    @Test
    void shouldFailClosedWhenOptimisticLastLoginUpdateLosesAConcurrentAccountChange() {
        AdminUserMapper userMapper = mock(AdminUserMapper.class);
        LoginAuditMapper auditMapper = mock(LoginAuditMapper.class);
        ConsoleSessionService consoleSessionService = mock(ConsoleSessionService.class);
        PrincipalResolver principalResolver = mock(PrincipalResolver.class);
        LoginSuccessService service = new LoginSuccessService(
                userMapper, auditMapper, consoleSessionService, mock(UserService.class),
                mock(DirectoryGroupSyncService.class), principalResolver);
        AdminUser user = localUser();
        when(auditMapper.insert(any(LoginAudit.class))).thenReturn(1);
        when(userMapper.updateById(user)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.issueExisting(user, "person@example.com", "203.0.113.7"));

        assertEquals("account state changed, retry login", exception.getMessage());
        verifyNoInteractions(consoleSessionService, principalResolver);
    }

    private AdminUser localUser() {
        AdminUser user = new AdminUser();
        user.setId(1L);
        user.setUserId("usr_1");
        user.setTenantId("tnt_1");
        user.setUsername("person@example.com");
        user.setPasswordHash("bcrypt-hash");
        user.setSource(UserSource.LOCAL);
        user.setStatus(UserStatus.ENABLED);
        user.setLockVersion(3);
        return user;
    }
}
