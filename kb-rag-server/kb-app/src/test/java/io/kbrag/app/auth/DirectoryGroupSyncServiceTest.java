package io.kbrag.app.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.RoleGrantSource;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the directory group synchronisation of the M16 contract section 6: the mapping parser that
 * must survive the '=' characters inside a DN and skip malformed entries instead of failing the
 * login, the full delete-then-insert replacement of the {@code LDAP_SYNC} grant set that never
 * touches a {@code MANUAL} grant, the refusal to grant a role of another tenant through the mapping,
 * and the startup warning for the "enabled but unmapped" configuration slip.
 *
 * <p>The "group lookup failed" contract clause is exercised through its visible half: the lookup
 * degrades to an empty group list inside the authenticator, and this service must then strip the
 * synchronised grants without inserting or throwing - the login itself already succeeded.
 *
 * @author owlzhangfq@gmail.com
 */
class DirectoryGroupSyncServiceTest {

    private static final String USER_ID = "usr_1";
    private static final String USERNAME = "alice";
    private static final String ADMIN_DN = "CN=kb-admins,OU=Groups,DC=corp,DC=example";
    private static final String ADMIN_CODE = "KB_ADMIN";
    private static final String ADMIN_MAPPING = ADMIN_DN + "=" + ADMIN_CODE;

    private KbProperties properties;
    private RoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private PrincipalResolver principalResolver;
    private DirectoryGroupSyncService service;
    private ListAppender<ILoggingEvent> logWatcher;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(Role.class);
        properties = new KbProperties();
        properties.getAuth().getLdap().getGroupSync().setEnabled(true);
        properties.getAuth().getLdap().getGroupSync().setRoleMappings(ADMIN_MAPPING);
        roleMapper = mock(RoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        principalResolver = mock(PrincipalResolver.class);
        service = new DirectoryGroupSyncService(properties, roleMapper, userRoleMapper,
                principalResolver);
        logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(DirectoryGroupSyncService.class)).addAppender(logWatcher);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DirectoryGroupSyncService.class))
                .detachAppender(logWatcher);
    }

    @Test
    void shouldSplitAnEntryOnItsLastEqualsSign() {
        // The DN itself is full of '=' characters; only the last one can separate the role code.
        Map<String, String> mappings = service.parseMappings(ADMIN_MAPPING);

        assertEquals(Map.of("cn=kb-admins,ou=groups,dc=corp,dc=example", ADMIN_CODE), mappings);
    }

    @Test
    void shouldFoldCaseAndWhitespaceOfTheDn() {
        // Directories return DNs with inconsistent casing and spacing between deployments; a
        // mapping missing over "CN=x, DC=y" versus "cn=x,dc=y" is invisible in a review.
        Map<String, String> mappings = service.parseMappings("CN = KB-Admins , DC = Corp=" + ADMIN_CODE);

        assertEquals(Map.of("cn=kb-admins,dc=corp", ADMIN_CODE), mappings);
    }

    @Test
    void shouldSkipMalformedEntriesInsteadOfFailingTheLogin() {
        // One typo in a deployment knob must not take single sign on down for everyone.
        Map<String, String> mappings = service.parseMappings(
                "no-separator;=ORPHAN_CODE;cn=orphan,dc=corp=;;" + ADMIN_MAPPING);

        assertEquals(Map.of("cn=kb-admins,ou=groups,dc=corp,dc=example", ADMIN_CODE), mappings);
    }

    @Test
    void shouldParseABlankConfigurationToNoMappings() {
        assertEquals(Map.of(), service.parseMappings(null));
        assertEquals(Map.of(), service.parseMappings("   "));
    }

    @Test
    void shouldReplaceTheSynchronisedGrantSetInFull() {
        when(roleMapper.selectList(any())).thenReturn(List.of(role(ADMIN_CODE, "role_admin", null)));

        service.sync(user(), List.of("cn=kb-admins, ou=groups, dc=corp, dc=example"));

        // Delete first, then insert: the replacement is idempotent no matter what a previous crash
        // left behind, and only the LDAP_SYNC half is touched - MANUAL grants stay untraceable-proof.
        InOrder order = inOrder(userRoleMapper);
        order.verify(userRoleMapper)
                .deleteByUserIdAndGrantedBy(USER_ID, RoleGrantSource.LDAP_SYNC.name());
        ArgumentCaptor<UserRole> inserted = ArgumentCaptor.forClass(UserRole.class);
        order.verify(userRoleMapper).insert(inserted.capture());
        assertEquals("role_admin", inserted.getValue().getRoleId());
        assertEquals(RoleGrantSource.LDAP_SYNC, inserted.getValue().getGrantedBy());
        verify(principalResolver).evict(USERNAME);
    }

    @Test
    void shouldStripTheGrantsWhenTheDirectoryReportsNoGroups() {
        // The degraded path of a failed group lookup: the authenticator hands over an empty list
        // and the login proceeds - the sync strips what the groups no longer justify, inserts
        // nothing, and throws nothing.
        service.sync(user(), List.of());

        verify(userRoleMapper)
                .deleteByUserIdAndGrantedBy(USER_ID, RoleGrantSource.LDAP_SYNC.name());
        verify(userRoleMapper, never()).insert(any(UserRole.class));
        verify(principalResolver).evict(USERNAME);
    }

    @Test
    void shouldNeverGrantARoleOfAnotherTenantThroughTheMapping() {
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role(ADMIN_CODE, "role_foreign", "tnt_other")));

        service.sync(user(), List.of(ADMIN_DN));

        // A role of another tenant granted here would be the cross tenant leak the row fence
        // exists to prevent; the mapping is skipped with a warning instead.
        verify(userRoleMapper, never()).insert(any(UserRole.class));
    }

    @Test
    void shouldSkipAMappingNamingAnUnknownRole() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        service.sync(user(), List.of(ADMIN_DN));

        // The mapping may name a role an operator has since deleted, and the person still
        // authenticated - the grant is skipped, the login is not failed.
        verify(userRoleMapper, never()).insert(any(UserRole.class));
        verify(principalResolver).evict(USERNAME);
    }

    @Test
    void shouldReportAtStartupWhenEnabledWithoutAnyUsableMapping() {
        properties.getAuth().getLdap().getGroupSync().setRoleMappings("malformed-entry");

        service.run(null);

        // Enabled-but-unmapped would strip every synchronised role on the next login and grant
        // nothing back, which looks like a permission bug - it must be named at startup.
        assertTrue(logWatcher.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    @Test
    void shouldStayQuietAtStartupWhenTheMappingIsUsable() {
        service.run(null);

        assertTrue(logWatcher.list.stream().noneMatch(event -> event.getLevel() == Level.ERROR));
    }

    private AdminUser user() {
        AdminUser user = new AdminUser();
        user.setUserId(USER_ID);
        user.setUsername(USERNAME);
        user.setTenantId(BuiltinTenants.DEFAULT_TENANT_ID);
        return user;
    }

    private Role role(String code, String roleId, String tenantId) {
        Role role = new Role();
        role.setRoleId(roleId);
        role.setCode(code);
        role.setTenantId(tenantId);
        return role;
    }
}
