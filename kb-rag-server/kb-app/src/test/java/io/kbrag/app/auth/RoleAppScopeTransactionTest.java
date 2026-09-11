package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.common.exception.BizException;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.mapper.DocAclMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.PermissionMapper;
import io.kbrag.domain.mapper.RoleAppScopeMapper;
import io.kbrag.domain.mapper.RoleKbScopeMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实 Mapper、租户插件与 Spring 事务共同验证范围隔离和授权原子性。 */
class RoleAppScopeTransactionTest {

    private static final String AUDIT = "id BIGINT AUTO_INCREMENT PRIMARY KEY, created_at TIMESTAMP, "
            + "updated_at TIMESTAMP, lock_version INT DEFAULT 0, deleted INT DEFAULT 0";
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private RoleService roles;
    private AppMapper apps;
    private PrincipalResolver principals;
    private RoleAppScopeMapper scopeMapper;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:role_scope_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE t_kb_role (" + AUDIT + ", role_id VARCHAR(64), tenant_id VARCHAR(64), "
                + "code VARCHAR(64), name VARCHAR(64), description VARCHAR(255), builtin INT DEFAULT 0, "
                + "kb_scope_all INT DEFAULT 1, app_scope_all BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE t_kb_app (" + AUDIT + ", app_id VARCHAR(64), tenant_id VARCHAR(64), "
                + "name VARCHAR(64), description VARCHAR(255))");
        jdbc.execute("CREATE TABLE t_kb_role_app (" + AUDIT + ", role_id VARCHAR(64), app_id VARCHAR(64), "
                + "UNIQUE(role_id, app_id))");
        jdbc.execute("CREATE TABLE t_kb_role_kb (" + AUDIT + ", role_id VARCHAR(64), kb_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE t_kb_role_permission (" + AUDIT
                + ", role_id VARCHAR(64), permission_code VARCHAR(64))");
        jdbc.execute("INSERT INTO t_kb_role(role_id,tenant_id,code,name) "
                + "VALUES('role_b','tenant_b','EMPLOYEE','原角色')");
        jdbc.execute("INSERT INTO t_kb_app(app_id,tenant_id,name,deleted) "
                + "VALUES('app_a','tenant_a','A',0),('app_b','tenant_b','B',0),"
                + "('app_deleted','tenant_b','Deleted',1)");
        jdbc.execute("INSERT INTO t_kb_role_app(role_id,app_id) VALUES('role_b','app_b')");
        jdbc.execute("INSERT INTO t_kb_role_kb(role_id,kb_id) VALUES('role_b','kb_old')");
        jdbc.execute("INSERT INTO t_kb_role_permission(role_id,permission_code) VALUES('role_b','app:use')");

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new AuditFieldFiller()));
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        for (Class<?> type : List.of(RoleMapper.class, AppMapper.class, RoleAppScopeMapper.class,
                RoleKbScopeMapper.class, RolePermissionMapper.class)) config.addMapper(type);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        apps = session.getMapper(AppMapper.class);
        scopeMapper = session.getMapper(RoleAppScopeMapper.class);
        var scopes = new RoleAppScopeService(scopeMapper, apps);
        principals = mock(PrincipalResolver.class);
        var ids = mock(BizIdGenerator.class);
        when(ids.roleId()).thenReturn("role_created");
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(RoleService.class, () -> new RoleService(session.getMapper(RoleMapper.class),
                mock(PermissionMapper.class), session.getMapper(RolePermissionMapper.class),
                session.getMapper(RoleKbScopeMapper.class), mock(UserRoleMapper.class), mock(DocAclMapper.class),
                mock(KnowledgeBaseMapper.class), ids, principals, scopes));
        context.refresh();
        roles = context.getBean(RoleService.class);
        bind("tenant_a", Set.of("tenant:manage", "role:manage"));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        if (context != null) context.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void shouldRollbackRolePermissionsAndBothScopesWhenApplicationBelongsToAnotherTenant() {
        assertThrows(BizException.class, () -> roles.update("role_b", "新名称", "说明",
                true, List.of(), List.of(), false, List.of("app_a")));

        assertEquals("原角色", roles.get("role_b").getName());
        assertEquals(0, roles.get("role_b").getLockVersion());
        assertEquals(List.of("app:use"), roles.permissionCodesOf("role_b"));
        assertEquals(List.of("kb_old"), roles.kbScopeOf("role_b"));
        assertEquals(List.of("app_b"), scopedIds());
        verifyNoInteractions(principals);
    }

    @Test
    void shouldUseTargetRoleTenantAndPreserveExplicitNoneVersusAll() {
        roles.update("role_b", "员工", null, true, List.of(), List.of(), false, List.of("app_b", "app_b"));
        assertEquals(List.of("app_b"), scopedIds());
        roles.update("role_b", "员工", null, true, List.of(), List.of(), false, List.of());
        assertTrue(scopedIds().isEmpty());
        assertFalse(roles.get("role_b").appScopeAll());
        roles.update("role_b", "员工", null, true, List.of(), List.of(), true, List.of());
        assertTrue(roles.get("role_b").appScopeAll());
        assertTrue(scopedIds().isEmpty());
    }

    @Test
    void shouldPreserveApplicationScopeWhenAnOlderClientUpdatesTheRole() {
        roles.update("role_b", "改名", null, true, List.of(), List.of(), null, null);
        assertEquals(List.of("app_b"), scopedIds());
        assertFalse(roles.get("role_b").appScopeAll());
    }

    @Test
    void shouldDefaultNewRoleToNoApplicationsAndExplicitCurrentTenant() {
        var created = roles.create("NEW_EMPLOYEE", "员工", null, true, List.of(), List.of(), null, null);
        assertEquals("tenant_a", created.getTenantId());
        assertFalse(created.appScopeAll());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_role_app WHERE role_id='role_created'", Integer.class));
    }

    @Test
    void shouldFenceScopeQueriesEvenForPlatformOperatorsOrBackgroundThreads() {
        assertEquals(List.of("app_b"), apps.listInTenant("tenant_b", null).stream().map(App::getAppId).toList());
        assertTrue(apps.listInTenant("tenant_b", List.of()).isEmpty());
        assertTrue(apps.listInTenant("tenant_b", List.of("app_a", "app_deleted")).isEmpty());
        UserContextHolder.clear();
        assertEquals(1, apps.listInTenant("tenant_b", null).size());
        assertTrue(apps.listInTenant(null, null).isEmpty());
    }

    @Test
    void shouldRefuseAnOrdinaryOperatorEditingAnotherTenantRole() {
        bind("tenant_a", Set.of("role:manage"));
        assertThrows(BizException.class, () -> roles.update("role_b", "越界", null,
                true, List.of(), List.of(), true, List.of()));
        assertEquals(List.of("app_b"), scopedIds());
    }

    private List<String> scopedIds() {
        return jdbc.queryForList("SELECT app_id FROM t_kb_role_app WHERE role_id='role_b'", String.class);
    }

    @Test
    void shouldRemoveScopeBindingsWhenTheOwningApplicationIsDeleted() {
        var service = new AppService(apps, mock(AppVersionMapper.class), mock(BizIdGenerator.class), scopeMapper);
        assertThrows(BizException.class, () -> service.delete("app_b"));
        assertEquals(List.of("app_b"), scopedIds());
        bind("tenant_b", Set.of("app:write"));
        service.delete("app_b");
        assertTrue(scopedIds().isEmpty());
        assertTrue(apps.listInTenant("tenant_b", null).isEmpty());
    }

    private void bind(String tenant, Set<String> permissions) {
        UserContextHolder.set(new UserPrincipal("usr_operator", tenant, "operator", "Operator", UserSource.LOCAL,
                Set.of(), Set.of(), permissions, true, Set.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {
    }
}
