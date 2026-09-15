package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.kbrag.domain.mapper.AppCorpusDocumentMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生产 SQL 验证版本归属和租户联合约束，可在独立 MySQL 测试库复用。 */
class AppCorpusDocumentMapperTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private AppCorpusDocumentMapper mapper;

    @BeforeEach
    void setUp() {
        String url = System.getenv("KB_CORPUS_TEST_JDBC_URL");
        if (url != null && !url.matches("jdbc:mysql://[^/]+/codex_corpus_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Corpus tests require a dedicated codex_corpus_ schema");
        }
        var source = url == null
                ? new DriverManagerDataSource("jdbc:h2:mem:corpus_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(url, System.getenv("KB_CORPUS_TEST_USER"), System.getenv("KB_CORPUS_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE t_kb_knowledge_base (kb_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), deleted INT)");
        jdbc.execute("CREATE TABLE t_kb_document (doc_id VARCHAR(64) PRIMARY KEY, kb_id VARCHAR(64), file_name VARCHAR(255), deleted INT)");
        jdbc.execute("CREATE TABLE t_kb_document_version (version_id VARCHAR(64) PRIMARY KEY, doc_id VARCHAR(64), version VARCHAR(64), deleted INT)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(AppCorpusDocumentMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(AppCorpusDocumentMapper.class);
        jdbc.update("INSERT INTO t_kb_knowledge_base VALUES ('kb','tenant',0), ('other-kb','tenant',0), ('foreign','other-tenant',0)");
        jdbc.update("INSERT INTO t_kb_document VALUES ('doc','kb','资料.md',0), ('other','other-kb','其他资料.md',0), ('foreign-doc','foreign','外部资料.md',0)");
        jdbc.update("INSERT INTO t_kb_document_version VALUES ('v1','doc','1.0',0), ('v2','doc','2.0',0), ('other-v','other','1.0',0), ('foreign-v','foreign-doc','1.0',0)");
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (jdbc != null) for (String table : List.of("t_kb_document_version", "t_kb_document", "t_kb_knowledge_base")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void membershipRequiresVersionDocumentKnowledgeBaseAndTenantToAgree() {
        var result = mapper.selectVersions("tenant", "kb", List.of("v1", "other-v", "foreign-v", "x' OR '1'='1"));
        assertEquals(1, result.size());
        assertEquals("v1", result.get(0).versionId());
        assertEquals("doc", result.get(0).docId());
        assertEquals("资料.md", result.get(0).fileName());
        assertEquals("1.0", result.get(0).version());
        assertTrue(mapper.selectVersions("tenant", "foreign", List.of("foreign-v")).isEmpty());
    }

    @Test
    void deletedVersionsDocumentsAndRootsNeverResolve() {
        jdbc.update("UPDATE t_kb_document_version SET deleted=1 WHERE version_id='v1'");
        assertEquals(List.of("v2"), mapper.selectVersions("tenant", "kb", List.of("v1", "v2")).stream().map(row -> row.versionId()).toList());
        jdbc.update("UPDATE t_kb_document SET deleted=1 WHERE doc_id='doc'");
        session.clearCache();
        assertTrue(mapper.selectVersions("tenant", "kb", List.of("v2")).isEmpty());
        jdbc.update("UPDATE t_kb_knowledge_base SET deleted=1 WHERE kb_id='other-kb'");
        session.clearCache();
        assertTrue(mapper.selectVersions("tenant", "other-kb", List.of("other-v")).isEmpty());
    }
}
