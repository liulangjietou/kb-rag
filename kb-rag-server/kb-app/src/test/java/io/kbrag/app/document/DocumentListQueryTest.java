package io.kbrag.app.document;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.index.RebuildService;
import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.mapper.DocumentMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 使用真实 MyBatis 分页与 SQL，覆盖组合筛选、字面关键词和接入关联去重。 */
class DocumentListQueryTest {

    private SqlSession session;
    private JdbcTemplate jdbc;
    private DocumentService service;

    @BeforeEach
    void createDatabase() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:documents_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE t_kb_document (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, doc_id VARCHAR(64), kb_id VARCHAR(64),
                    file_name VARCHAR(256), file_ext VARCHAR(16), file_size BIGINT, current_version_id VARCHAR(64),
                    process_status VARCHAR(32), visibility VARCHAR(32), publish_status VARCHAR(32),
                    review_note VARCHAR(128), effective_at TIMESTAMP, expires_at TIMESTAMP, trashed INT DEFAULT 0,
                    trashed_at TIMESTAMP, config_stale INT DEFAULT 0, fail_reason VARCHAR(128), source_key VARCHAR(128),
                    created_at TIMESTAMP, updated_at TIMESTAMP, lock_version INT DEFAULT 0, deleted INT DEFAULT 0)
                """);
        jdbc.execute("CREATE TABLE t_kb_web_source (kb_id VARCHAR(64), doc_id VARCHAR(64), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_kb_ext_source (source_id VARCHAR(64), kb_id VARCHAR(64), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_kb_ext_source_item (source_id VARCHAR(64), doc_id VARCHAR(64), deleted INT DEFAULT 0)");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        configuration.addInterceptor(pagination);
        configuration.addMapper(DocumentMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        service = new DocumentService(session.getMapper(DocumentMapper.class), null, null, null, null,
                null, null, null, mock(KnowledgeBaseService.class), null, null, null, null, null);
    }

    @AfterEach
    void closeDatabase() {
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void shouldFilterBeforePaginationAndIncludeLegacyPublishedState() {
        insert("old", "kb_1", "目标文档旧版", "INDEXED", "PUBLISHED", "2026-08-01T00:00:00");
        insert("first", "kb_1", "目标文档一", "INDEXED", null, "2026-09-01T00:00:00");
        insert("second", "kb_1", "目标文档二", "INDEXED", "PUBLISHED", "2026-09-02T00:00:00");
        insert("draft", "kb_1", "目标文档草稿", "INDEXED", "DRAFT", "2026-09-02T00:00:00");
        insert("other_tenant", "kb_2", "目标文档三", "INDEXED", "PUBLISHED", "2026-09-02T00:00:00");
        insert("trashed", "kb_1", "目标文档回收", "INDEXED", "PUBLISHED", "2026-09-02T00:00:00");
        jdbc.update("UPDATE t_kb_document SET trashed=1 WHERE doc_id='trashed'");
        DocumentListFilter filter = new DocumentListFilter(" 目标文档 ", ProcessStatus.INDEXED,
                PublishStatus.PUBLISHED, null, LocalDateTime.parse("2026-09-01T00:00:00"),
                LocalDateTime.parse("2026-09-02T00:00:00"));

        IPage<Document> first = service.list("kb_1", filter, 1, 1);
        IPage<Document> second = service.list("kb_1", filter, 2, 1);

        assertEquals(2, first.getTotal());
        assertEquals(List.of("second"), ids(first));
        assertEquals(List.of("first"), ids(second));
    }

    @Test
    void shouldTreatWildcardsAndSqlCharactersAsLiteralFilenameContent() {
        insert("literal", "kb_1", "100%_!制度.pdf", "INDEXED", "PUBLISHED", "2026-09-01T00:00:00");
        insert("ordinary", "kb_1", "100百分比制度.pdf", "INDEXED", "PUBLISHED", "2026-09-01T00:00:00");
        assertEquals(List.of("literal"), ids(service.list("kb_1",
                new DocumentListFilter("%_!", null, null, null, null, null), 1, 20)));
        assertEquals(0, service.list("kb_1",
                new DocumentListFilter("' OR 1=1 --", null, null, null, null, null), 1, 20).getTotal());
    }

    @Test
    void shouldUseScopedSourceBindingsWithoutDuplicatingDocuments() {
        for (String id : List.of("upload", "web", "external", "chat")) {
            insert(id, "kb_1", id + ".txt", "INDEXED", "PUBLISHED", "2026-09-01T00:00:00");
        }
        jdbc.update("UPDATE t_kb_document SET source_key='chat:session_1' WHERE doc_id='chat'");
        jdbc.update("INSERT INTO t_kb_web_source VALUES ('kb_1','web',0),('kb_1','web',1),('kb_2','upload',0)");
        jdbc.update("INSERT INTO t_kb_ext_source VALUES ('s1','kb_1',0),('s2','kb_2',0)");
        jdbc.update("INSERT INTO t_kb_ext_source_item VALUES ('s1','external',0),('s1','external',1),('s2','upload',0)");
        for (DocumentListFilter.Source source : DocumentListFilter.Source.values()) {
            assertEquals(List.of(source.name().toLowerCase(java.util.Locale.ROOT)), ids(service.list("kb_1",
                    new DocumentListFilter(null, null, null, source, null, null), 1, 20)));
        }
    }

    @Test
    void shouldExcludeTrashedDocumentsFromCatchUpCountsAndAutomaticRebuild() {
        insert("live", "kb_1", "保留.txt", "INDEXED", "PUBLISHED", "2026-09-01T00:00:00");
        insert("trash", "kb_1", "已回收.txt", "INDEXED", "PUBLISHED", "2026-09-01T00:00:00");
        jdbc.update("UPDATE t_kb_document SET config_stale=1, current_version_id=doc_id");
        jdbc.update("UPDATE t_kb_document SET trashed=1 WHERE doc_id='trash'");
        RebuildService rebuild = new RebuildService(session.getMapper(DocumentMapper.class), service,
                mock(IndexPipelineService.class), mock(KnowledgeBaseService.class));

        assertEquals(1, rebuild.status("kb_1").staleCount());
        assertEquals(List.of("live"), rebuild.submit("kb_1", null));
    }

    @Test
    void shouldCountFirstUploadsAcrossAllPagesButNotOtherBasesTrashOrManualConfirmation() {
        for (String id : List.of("fresh", "indexed", "manual", "trash", "other")) {
            insert(id, id.equals("other") ? "kb_2" : "kb_1", id + ".txt", "PARSING", "PUBLISHED",
                    "2026-09-01T00:00:00");
        }
        jdbc.update("UPDATE t_kb_document SET process_status='INDEXED' WHERE doc_id='indexed'");
        jdbc.update("UPDATE t_kb_document SET process_status='PENDING_CONFIRM' WHERE doc_id='manual'");
        jdbc.update("UPDATE t_kb_document SET trashed=1 WHERE doc_id='trash'");
        RebuildService rebuild = new RebuildService(session.getMapper(DocumentMapper.class), service,
                mock(IndexPipelineService.class), mock(KnowledgeBaseService.class));

        RebuildService.RebuildStatus status = rebuild.status("kb_1");
        assertEquals(3, status.documentCount());
        assertEquals(1, status.processingCount());
        assertEquals(0, status.inProgressCount());
    }

    @Test
    void shouldRejectReversedDatesAndOversizedKeywordsAtCriteriaCreation() {
        assertThrows(BizException.class, () -> new DocumentListFilter("a".repeat(201), null, null, null, null, null));
        assertThrows(BizException.class, () -> new DocumentListFilter(null, null, null, null,
                LocalDateTime.parse("2026-09-02T00:00:00"), LocalDateTime.parse("2026-09-01T00:00:00")));
    }

    private void insert(String id, String kbId, String name, String process, String publish, String updated) {
        jdbc.update("INSERT INTO t_kb_document(doc_id,kb_id,file_name,process_status,publish_status,updated_at) "
                + "VALUES (?,?,?,?,?,?)", id, kbId, name, process, publish, LocalDateTime.parse(updated));
    }

    private List<String> ids(IPage<Document> page) {
        return page.getRecords().stream().map(Document::getDocId).toList();
    }
}
