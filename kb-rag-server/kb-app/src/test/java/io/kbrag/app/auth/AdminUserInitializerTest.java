package io.kbrag.app.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserInitializerTest {

    @Test
    void shouldClaimAnEmailShapedBootstrapUsernameBeforeInsert() {
        AdminUserMapper userMapper = mock(AdminUserMapper.class);
        EmailIdentityClaimService claimService = mock(EmailIdentityClaimService.class);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        BizIdGenerator idGenerator = mock(BizIdGenerator.class);
        UserService userService = mock(UserService.class);
        KbProperties properties = new KbProperties();
        properties.getAuth().setBootstrapUsername("owner@example.com");
        when(userMapper.selectCount(null)).thenReturn(0L);
        when(idGenerator.userId()).thenReturn("usr_bootstrap");
        when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
        AdminUserInitializer initializer = new AdminUserInitializer(
                userMapper, claimService, properties, encoder, idGenerator, userService);

        initializer.run(mock(ApplicationArguments.class));

        ArgumentCaptor<AdminUser> inserted = ArgumentCaptor.forClass(AdminUser.class);
        InOrder order = inOrder(claimService, userMapper);
        order.verify(claimService).claimForNewUser(
                "usr_bootstrap", "owner@example.com", null);
        order.verify(userMapper).insert(inserted.capture());
        assertEquals("owner@example.com", inserted.getValue().getUsername());
    }

    @Test
    void shouldNotClaimAnythingWhenAnAdministratorAlreadyExists() {
        AdminUserMapper userMapper = mock(AdminUserMapper.class);
        EmailIdentityClaimService claimService = mock(EmailIdentityClaimService.class);
        when(userMapper.selectCount(null)).thenReturn(1L);
        AdminUserInitializer initializer = new AdminUserInitializer(
                userMapper, claimService, new KbProperties(), mock(BCryptPasswordEncoder.class),
                mock(BizIdGenerator.class), mock(UserService.class));

        initializer.run(mock(ApplicationArguments.class));

        verifyNoInteractions(claimService);
    }
}
