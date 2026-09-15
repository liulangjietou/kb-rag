package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.WebSource;
import io.kbrag.domain.enums.KnowledgeTodoKind;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.mapper.KnowledgeTodoMapper;
import io.kbrag.domain.mapper.WebSourceMapper;
import io.kbrag.domain.model.UserPrincipal;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用生产 SQL 验证聚合范围、状态列语义和来源时间的迟到写入。 */
class KnowledgeMaintenancePersistenceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 10, 0);
    private final KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    private JdbcTemplate jdbc;
    private SqlSession session;
    private KnowledgeTodoService service;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:maintenance_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE t_kb_document(id BIGINT PRIMARY KEY,kb_id VARCHAR(64),process_status VARCHAR(32),publish_status VARCHAR(32),trashed INT,deleted INT)");
        for (String name : List.of("web", "ext")) {
            String status = name.equals("web") ? "last_fetch_status" : "last_sync_status";
            jdbc.execute("CREATE TABLE t_kb_" + name + "_source(id BIGINT PRIMARY KEY,kb_id VARCHAR(64),"
                    + status + " VARCHAR(32),deleted INT DEFAULT 0,lock_version INT DEFAULT 0,updated_at TIMESTAMP,"
                    + "last_success_at TIMESTAMP,last_content_change_at TIMESTAMP)");
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(KnowledgeTodoMapper.class);
        configuration.addMapper(WebSourceMapper.class);
        configuration.addMapper(ExtSourceMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        service = new KnowledgeTodoService(bases, session.getMapper(KnowledgeTodoMapper.class));
        KnowledgeBase allowed = new KnowledgeBase(); allowed.setKbId("allowed"); allowed.setName("当前知识库");
        when(bases.list()).thenReturn(List.of(allowed));
        bind("kb:read");
    }

    @AfterEach
    void close() {
        UserContextHolder.clear();
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void shouldCountOnlyAuthorizedLiveRootsAndUseTheCorrectDocumentStatusColumn() {
        jdbc.update("INSERT INTO t_kb_document VALUES (1,'allowed','PENDING_CONFIRM','DRAFT',0,0),(2,'allowed','INDEXED','PENDING_REVIEW',0,0),(3,'allowed','PENDING_CONFIRM','PENDING_REVIEW',1,0),(4,'hidden','PENDING_CONFIRM','PENDING_REVIEW',0,0),(5,'allowed','PENDING_CONFIRM','PENDING_REVIEW',0,1)");
        jdbc.update("INSERT INTO t_kb_web_source(id,kb_id,last_fetch_status,deleted) VALUES (1,'allowed','FAILED',0),(2,'hidden','FAILED',0),(3,'allowed','FAILED',1)");
        jdbc.update("INSERT INTO t_kb_ext_source(id,kb_id,last_sync_status) VALUES (1,'allowed','FAILED'),(2,'allowed','PARTIAL'),(3,'allowed','SUCCESS'),(4,'hidden','FAILED')");
        var todos = service.list();
        assertEquals(4, todos.size());
        assertEquals(5, todos.stream().mapToLong(KnowledgeTodoService.Todo::total).sum());
        assertTrue(todos.stream().allMatch(item -> item.kbId().equals("allowed") && !item.canProcess()));
        assertEquals(KnowledgeTodoKind.EXT_SOURCE_ATTENTION, todos.get(0).kind());
        bind("kb:read", "doc:review");
        assertEquals(List.of(KnowledgeTodoKind.PENDING_REVIEW), service.list().stream()
                .filter(KnowledgeTodoService.Todo::canProcess).map(KnowledgeTodoService.Todo::kind).toList());
        when(bases.list()).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void shouldRejectWithoutKnowledgeReadPermissionBeforeReadingAnyRoots() {
        bind("app:use");
        assertThrows(BizException.class, service::list);
        verify(bases, never()).list();
    }

    @Test
    void shouldKeepHealthMonotonicAndExcludeDeletedRowsInRealSql() {
        jdbc.update("INSERT INTO t_kb_web_source(id,kb_id,deleted) VALUES (1,'allowed',0),(2,'allowed',1)");
        jdbc.update("INSERT INTO t_kb_ext_source(id,kb_id,deleted) VALUES (1,'allowed',0),(2,'allowed',1)");
        var web = session.getMapper(WebSourceMapper.class);
        var ext = session.getMapper(ExtSourceMapper.class);
        for (LocalDateTime time : new LocalDateTime[]{NOW.plusHours(1), NOW, null}) {
            web.advanceHealthTimes(1L, time, time);
            ext.advanceHealthTimes(1L, time, time);
        }
        assertEquals(0, web.advanceHealthTimes(2L, NOW, NOW));
        assertEquals(0, ext.advanceHealthTimes(2L, NOW, NOW));
        for (String name : List.of("web", "ext")) {
            assertEquals(NOW.plusHours(1), jdbc.queryForObject("SELECT last_success_at FROM t_kb_" + name + "_source WHERE id=1", LocalDateTime.class));
            assertEquals(NOW.plusHours(1), jdbc.queryForObject("SELECT last_content_change_at FROM t_kb_" + name + "_source WHERE id=1", LocalDateTime.class));
        }
    }

    @Test
    void shouldNotOverwriteHealthThroughOrdinaryStaleEntityUpdates() {
        jdbc.update("INSERT INTO t_kb_web_source(id,kb_id,last_success_at) VALUES (1,'allowed',?)", NOW.plusHours(1));
        WebSource stale = new WebSource(); stale.setId(1L); stale.setLastSuccessAt(NOW);
        stale.setLastFetchStatus(WebSourceFetchStatus.FAILED);
        session.getMapper(WebSourceMapper.class).updateById(stale);
        assertEquals(NOW.plusHours(1), jdbc.queryForObject("SELECT last_success_at FROM t_kb_web_source WHERE id=1", LocalDateTime.class));
        jdbc.update("INSERT INTO t_kb_ext_source(id,kb_id,last_content_change_at) VALUES (1,'allowed',?)", NOW.plusHours(1));
        ExtSource other = new ExtSource(); other.setId(1L); other.setKbId("allowed"); other.setLastContentChangeAt(NOW);
        session.getMapper(ExtSourceMapper.class).updateById(other);
        assertEquals(NOW.plusHours(1), jdbc.queryForObject("SELECT last_content_change_at FROM t_kb_ext_source WHERE id=1", LocalDateTime.class));
    }

    private void bind(String... permissions) {
        UserContextHolder.set(new UserPrincipal("user", "tenant", "reader", "使用者", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(permissions), true, Set.of()));
    }
}
