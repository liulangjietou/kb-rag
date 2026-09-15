package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.ResourceVisitKind;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.ResourceVisitMapper;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 生产 Mapper 验证个人范围、当前授权筛选、真实访问排序和重复写入语义。 */
class ResourceVisitServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 10, 0);
    private final KbResourceGuard guard = mock(KbResourceGuard.class);
    private final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    private final AppService apps = mock(AppService.class);
    private JdbcTemplate jdbc;
    private SqlSession session;
    private ResourceVisitMapper visits;
    private ResourceVisitService service;

    @BeforeEach
    void setUp() throws Exception {
        String mysqlUrl = System.getenv("KB_VISIT_TEST_JDBC_URL");
        if (mysqlUrl != null && !mysqlUrl.matches("jdbc:mysql://[^/]+/codex_resource_visit_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Visit tests require a dedicated codex_resource_visit_ schema");
        }
        var source = mysqlUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:visits_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(mysqlUrl, System.getenv("KB_VISIT_TEST_USER"), System.getenv("KB_VISIT_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        String ddl = Files.readString(Path.of("../kb-api/src/main/resources/db/migration/V33__personal_resource_visits.sql"));
        jdbc.execute(mysqlUrl == null ? ddl.replaceAll("ENGINE = InnoDB[^;]+", "") : ddl);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(ResourceVisitMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        visits = session.getMapper(ResourceVisitMapper.class);
        service = new ResourceVisitService(visits, guard, knowledgeBases, apps);
        bind("kb:read", "app:read", "tenant:manage");
        when(knowledgeBases.list()).thenReturn(List.of(kb("kb_a")));
        when(apps.list()).thenReturn(List.of(app("app_a")));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP TABLE IF EXISTS t_kb_resource_visit");
    }

    @Test
    void shouldRecordOnlyAfterTheSelectedResourceIsAuthorized() {
        service.remember(ResourceVisitKind.KB, "kb_a");
        service.remember(ResourceVisitKind.APP, "app_a");
        verify(guard).requireKb("kb_a");
        verify(apps).require("app_a");
        assertEquals(2, count());
        assertEquals(List.of("app_a", "kb_a"), service.recent().stream().map(ResourceVisitService.RecentVisit::resourceId).toList());
        assertEquals("tenant_a", jdbc.queryForObject("SELECT DISTINCT tenant_id FROM t_kb_resource_visit", String.class));
        assertEquals("user_a", jdbc.queryForObject("SELECT DISTINCT user_id FROM t_kb_resource_visit", String.class));
    }

    @Test
    void shouldRequireTheSpecificKindsReadPermissionBeforeLookingUpTheResource() {
        bind("app:read");
        assertThrows(BizException.class, () -> service.remember(ResourceVisitKind.KB, "kb_a"));
        bind("kb:read");
        assertThrows(BizException.class, () -> service.remember(ResourceVisitKind.APP, "app_a"));
        verifyNoInteractions(guard, apps);
        assertEquals(0, count());
    }

    @Test
    void shouldNotRecordDeletedOrInaccessibleResources() {
        doThrow(BizException.forbidden("outside scope")).when(guard).requireKb("kb_hidden");
        when(apps.require("app_hidden")).thenThrow(BizException.notFound("application not found"));
        assertThrows(BizException.class, () -> service.remember(ResourceVisitKind.KB, "kb_hidden"));
        assertThrows(BizException.class, () -> service.remember(ResourceVisitKind.APP, "app_hidden"));
        assertEquals(0, count());
    }

    @Test
    void shouldApplyTenantOwnerAndCurrentResourceScopeBeforeTheLimit() {
        var allowed = IntStream.range(0, 7).mapToObj(i -> kb("kb_" + i)).toList();
        when(knowledgeBases.list()).thenReturn(allowed);
        for (int i = 0; i < 7; i++) seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_" + i, NOW.plusMinutes(i));
        for (int i = 0; i < 6; i++) seed("tenant_a", "user_a", ResourceVisitKind.KB, "revoked_" + i, NOW.plusDays(1));
        seed("tenant_b", "user_a", ResourceVisitKind.KB, "kb_0", NOW.plusDays(1));
        seed("tenant_a", "user_b", ResourceVisitKind.KB, "kb_0", NOW.plusDays(1));
        seed("tenant_a", "user_a", ResourceVisitKind.APP, "kb_0", NOW.plusDays(1));
        seed("tenant_a", "user_a", ResourceVisitKind.APP, "app_a", NOW.plusDays(1));
        jdbc.update("UPDATE t_kb_resource_visit SET deleted=1 WHERE resource_id='app_a'");
        assertEquals(List.of("kb_6", "kb_5", "kb_4", "kb_3", "kb_2"), service.recent().stream()
                .map(ResourceVisitService.RecentVisit::resourceId).toList());
    }

    @Test
    void shouldRefreshNamesAndRemoveRevokedKindsWithoutReadingTheirCatalog() {
        seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_a", NOW);
        seed("tenant_a", "user_a", ResourceVisitKind.APP, "app_a", NOW.plusMinutes(1));
        var renamed = kb("kb_a"); renamed.setName("新的知识库名称");
        when(knowledgeBases.list()).thenReturn(List.of(renamed));
        bind("kb:read");
        assertEquals(List.of("新的知识库名称"), service.recent().stream().map(ResourceVisitService.RecentVisit::name).toList());
        verify(apps, never()).list();
        when(knowledgeBases.list()).thenReturn(List.of());
        assertTrue(service.recent().isEmpty());
    }

    @Test
    void shouldKeepOneVisitAndNeverMoveItsTimestampBackwards() {
        seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_a", NOW.plusMinutes(1));
        seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_a", NOW);
        seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_a", NOW.plusMinutes(1));
        assertEquals(1, count());
        assertEquals(NOW.plusMinutes(1), service.recent().get(0).visitedAt());
    }

    @Test
    void shouldClearOnlyTheCurrentUsersRecords() {
        seed("tenant_a", "user_a", ResourceVisitKind.KB, "kb_a", NOW);
        seed("tenant_a", "user_a", ResourceVisitKind.APP, "app_a", NOW);
        seed("tenant_a", "user_b", ResourceVisitKind.KB, "kb_a", NOW);
        seed("tenant_b", "user_a", ResourceVisitKind.KB, "kb_a", NOW);
        service.clear();
        assertTrue(service.recent().isEmpty());
        assertEquals(2, count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_resource_visit WHERE tenant_id='tenant_a' AND user_id='user_a'", Integer.class));
    }

    private void seed(String tenant, String user, ResourceVisitKind kind, String id, LocalDateTime time) {
        visits.remember(tenant, user, kind, id, time);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_resource_visit", Integer.class);
    }

    private KnowledgeBase kb(String id) {
        var kb = new KnowledgeBase(); kb.setKbId(id); kb.setName(id); return kb;
    }

    private App app(String id) {
        var app = new App(); app.setAppId(id); app.setName(id); return app;
    }

    private void bind(String... permissions) {
        UserContextHolder.set(new UserPrincipal("user_a", "tenant_a", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permissions), true, Set.of()));
    }
}
