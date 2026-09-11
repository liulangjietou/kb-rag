package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 实际 MyBatis 查询验证 LIMIT 之前的身份、授权应用与软删除裁剪。 */
class EmployeeHomeServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 10, 0);
    private static final UserPrincipal OWNER = new UserPrincipal("user_a", "tenant_a", "employee", "员工",
            UserSource.LOCAL, Set.of(), Set.of(), Set.of("app:use", "tenant:manage"), true, Set.of(), true, Set.of());
    private final EmployeeWorkspaceAccess access = mock(EmployeeWorkspaceAccess.class);
    private final EmployeeAppCatalogService catalog = mock(EmployeeAppCatalogService.class);
    private JdbcTemplate jdbc;
    private SqlSession session;
    private EmployeeHomeService service;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:employee_home_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String migration = Files.readString(Path.of("../kb-api/src/main/resources/db/migration/V28__employee_conversations.sql"));
        for (String sql : migration.replaceAll("ENGINE = InnoDB[^;]+", "").split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(EmployeeConversationMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        service = new EmployeeHomeService(access, catalog, session.getMapper(EmployeeConversationMapper.class));
        UserContextHolder.set(OWNER);
        when(access.current()).thenReturn(OWNER);
        when(catalog.listFor(OWNER)).thenReturn(List.of(application("app_a"), application("app_b")));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void shouldFenceTenantUserApplicationAndDeletionBeforeLimiting() {
        seed("foreign_tenant", "tenant_b", "user_a", "app_a", NOW.plusDays(1), 0);
        seed("foreign_user", "tenant_a", "user_b", "app_a", NOW.plusDays(1), 0);
        seed("revoked_app", "tenant_a", "user_a", "app_revoked", NOW.plusDays(1), 0);
        seed("deleted", "tenant_a", "user_a", "app_a", NOW.plusDays(1), 1);
        for (int i = 0; i < 7; i++) seed("mine_" + i, "tenant_a", "user_a", i % 2 == 0 ? "app_a" : "app_b", NOW.plusMinutes(i), 0);

        var result = service.overview();

        assertEquals(List.of("mine_6", "mine_5", "mine_4", "mine_3", "mine_2"), result.recentConversations()
                .stream().map(EmployeeConversation::getConversationId).toList());
        assertEquals(2, result.applications().size());
        verify(access).current();
        verify(catalog).listFor(OWNER);
    }

    @Test
    void shouldUseIdAsStableTieBreakerForEqualActivityTimes() {
        seed("first", "tenant_a", "user_a", "app_a", NOW, 0);
        seed("second", "tenant_a", "user_a", "app_a", NOW, 0);
        assertEquals(List.of("second", "first"), service.overview().recentConversations().stream()
                .map(EmployeeConversation::getConversationId).toList());
    }

    @Test
    void shouldNotQueryConversationsWhenNoApplicationRemainsAccessible() {
        var mapper = mock(EmployeeConversationMapper.class);
        when(catalog.listFor(OWNER)).thenReturn(List.of());
        var result = new EmployeeHomeService(access, catalog, mapper).overview();
        assertTrue(result.applications().isEmpty());
        assertTrue(result.recentConversations().isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldStopBeforeReadingMetadataWhenCurrentAuthorizationFails() {
        when(access.current()).thenThrow(BizException.forbidden("revoked"));
        assertThrows(BizException.class, service::overview);
        verifyNoInteractions(catalog);
    }

    private void seed(String id, String tenant, String user, String app, LocalDateTime time, int deleted) {
        jdbc.update("INSERT INTO t_kb_conversation (conversation_id,tenant_id,user_id,app_id,title,last_turn,last_activity_at,deleted) "
                + "VALUES (?,?,?,?,?,0,?,?)", id, tenant, user, app, id, time, deleted);
    }

    private EmployeeAppCatalogService.ReleasedApplication application(String id) {
        App app = new App();
        app.setAppId(id);
        app.setName(id);
        return new EmployeeAppCatalogService.ReleasedApplication(app, new AppVersion());
    }
}
