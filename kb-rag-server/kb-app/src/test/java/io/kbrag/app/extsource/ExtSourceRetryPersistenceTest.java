package io.kbrag.app.extsource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.ExtSourceItem;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.ExtSourceItemStatus;
import io.kbrag.domain.mapper.ExtSourceItemMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ConnectorRouter;
import io.kbrag.common.util.HashUtil;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 MyBatis 更新语句，防止内存状态成功但数据库保留旧错误。 */
class ExtSourceRetryPersistenceTest {
    @Test
    void shouldClearThePersistedFailureWhenAnObjectRecovers() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:retry_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE t_kb_ext_source_item(id BIGINT PRIMARY KEY,last_status VARCHAR(32),"
                + "last_error VARCHAR(512),deleted INT DEFAULT 0,lock_version INT DEFAULT 0,updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO t_kb_ext_source_item(id,last_status,last_error) VALUES (1,'FAILED','old timeout')");
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        configuration.addMapper(ExtSourceItemMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var recovered = new ExtSourceItem(); recovered.setId(1L);
            recovered.setLastStatus(ExtSourceItemStatus.SUCCESS); recovered.setLastError(null);
            assertEquals(1, session.getMapper(ExtSourceItemMapper.class).updateById(recovered));
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT last_status FROM t_kb_ext_source_item WHERE id=1", String.class));
            assertNull(jdbc.queryForObject("SELECT last_error FROM t_kb_ext_source_item WHERE id=1", String.class));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void shouldSelectOnlyFailedRowsAndPaginateTheFilteredDatabaseResult() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:retry_selection_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE t_kb_ext_source_item(id BIGINT PRIMARY KEY,source_id VARCHAR(64),"
                + "object_key VARCHAR(1024),object_key_hash VARCHAR(64),etag VARCHAR(128),doc_id VARCHAR(64),"
                + "last_status VARCHAR(32),last_error VARCHAR(512),last_sync_at TIMESTAMP,created_at TIMESTAMP,"
                + "deleted INT DEFAULT 0,lock_version INT DEFAULT 0,updated_at TIMESTAMP)");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO t_kb_ext_source_item(id,source_id,object_key,object_key_hash,etag,last_status,last_error,deleted) VALUES (?,?,?,?,?,?,?,?)",
                    i, i == 3 ? "other_source" : "source_one", "object" + i + ".md",
                    HashUtil.sha256Hex("object" + i + ".md"), "v1", i == 2 ? "SUCCESS" : "FAILED", i == 2 ? null : "timeout", 0);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(ExtSourceItemMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var sources = mock(ExtSourceMapper.class);
            var source = new ExtSource(); source.setId(1L); source.setSourceId("source_one");
            source.setKbId("kb_one"); source.setSourceType("s3");
            when(sources.selectOne(any())).thenReturn(source);
            var bases = mock(KnowledgeBaseService.class);
            var base = new KnowledgeBase(); base.setTenantId("tenant_one");
            when(bases.find("kb_one")).thenReturn(base);
            var router = mock(ConnectorRouter.class); var connector = mock(ExternalConnector.class);
            when(router.resolve("s3")).thenReturn(connector);
            when(connector.testConnection(any())).thenReturn(HealthStatus.up("ok"));
            when(connector.listObjects(any())).thenReturn(List.of(
                    new ExternalConnector.RemoteObject("object1.md", null, "v1", 1, null),
                    new ExternalConnector.RemoteObject("object2.md", null, "v2", 1, null)));
            when(connector.fetchObject(any(), anyString())).thenReturn(new byte[]{1});
            var documents = mock(DocumentService.class); var document = new Document(); document.setDocId("doc_one");
            when(documents.upload(anyString(), anyString(), any()))
                    .thenReturn(new UploadOutcome(document, "dv_one", "v1", true, null));
            var service = new ExtSourceService(sources, session.getMapper(ExtSourceItemMapper.class),
                    mock(DocumentMapper.class), documents, bases, router, mock(BizIdGenerator.class),
                    new KbProperties(), mock(KbMetrics.class));
            var filtered = service.listItems("source_one", 1, 1, true);
            assertEquals(1, filtered.getTotal()); assertEquals("object1.md", filtered.getRecords().get(0).getObjectKey());
            assertEquals(2, service.listItems("source_one", 1, 1).getTotal());
            service.retryFailedSource("source_one");
            verify(connector).fetchObject(any(), eq("object1.md"));
            verify(connector, never()).fetchObject(any(), eq("object2.md"));
            assertEquals(0, service.listItems("source_one", 1, 1, true).getTotal());
            assertEquals("FAILED", jdbc.queryForObject("SELECT last_status FROM t_kb_ext_source_item WHERE id=3", String.class));
            assertNull(jdbc.queryForObject("SELECT last_sync_at FROM t_kb_ext_source_item WHERE id=2", java.sql.Timestamp.class));
            assertNull(jdbc.queryForObject("SELECT last_error FROM t_kb_ext_source_item WHERE id=1", String.class));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
