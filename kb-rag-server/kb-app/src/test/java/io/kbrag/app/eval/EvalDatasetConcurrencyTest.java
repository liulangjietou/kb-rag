package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.BizIdGenerator;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 生产乐观锁和真实事务必须一起保护用例内容、集合修订号与计数。 */
class EvalDatasetConcurrencyTest {
    private static final String DATASET = "evds_concurrent";
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private EvalDatasetService service;
    private EvalDatasetMapper datasets;
    private EvalDatasetMapper storedDatasets;
    private EvalCaseMapper cases;
    private EvalCaseMapper storedCases;

    @BeforeEach
    void setUp() throws Exception {
        String mysqlUrl = System.getenv("KB_EVAL_TEST_JDBC_URL");
        if (mysqlUrl != null && !mysqlUrl.matches("jdbc:mysql://[^/]+/codex_eval_consistency_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Evaluation tests require a dedicated codex_eval_consistency_ schema");
        }
        var source = mysqlUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:eval_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")
                : new DriverManagerDataSource(mysqlUrl, System.getenv("KB_EVAL_TEST_USER"), System.getenv("KB_EVAL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        Path migrations = Path.of("../kb-api/src/main/resources/db/migration");
        for (String name : List.of("V5__evaluation.sql", "V17__tenant_doc_acl_audit.sql", "V23__final_answer_evaluation.sql")) {
            String ddl = Files.readString(migrations.resolve(name)).replaceAll("(?m)^\\s*--.*$", "");
            for (String statement : ddl.split(";")) {
                if (!statement.matches("(?s)\\s*(CREATE TABLE|ALTER TABLE) t_kb_eval_(dataset|case)\\b.*")) continue;
                // H2 没有 MySQL JSON 的 JDBC 文本语义；MySQL 验证直接执行生产字段定义。
                String sql = mysqlUrl == null ? statement.replaceAll("(?s)ENGINE = InnoDB.*", "")
                        .replaceAll("\\bJSON\\b", "LONGTEXT").replaceAll(",\\s*ADD KEY idx_tenant \\(tenant_id\\)", "") : statement;
                jdbc.execute(sql);
            }
        }
        jdbc.update("INSERT INTO t_kb_eval_dataset (dataset_id,kb_id,name) VALUES (?,?,?)", DATASET, "kb_fixture", "并发验收");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new AuditFieldFiller()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(EvalDatasetMapper.class);
        configuration.addMapper(EvalCaseMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        storedDatasets = session.getMapper(EvalDatasetMapper.class);
        datasets = mock(EvalDatasetMapper.class, delegatesTo(storedDatasets));
        storedCases = session.getMapper(EvalCaseMapper.class);
        cases = mock(EvalCaseMapper.class, delegatesTo(storedCases));
        DocumentMapper documents = mock(DocumentMapper.class);
        Document document = new Document();
        document.setDocId("doc_fixture"); document.setKbId("kb_fixture"); document.setCurrentVersionId("dv_fixture");
        when(documents.selectOne(any())).thenReturn(document);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(EvalDatasetService.class, () -> new EvalDatasetService(datasets,
                cases, mock(EvalRunMapper.class), mock(EvalResultMapper.class),
                documents, mock(ChunkMapper.class), mock(KnowledgeBaseService.class), new BizIdGenerator()));
        context.refresh();
        service = context.getBean(EvalDatasetService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS t_kb_eval_case");
            jdbc.execute("DROP TABLE IF EXISTS t_kb_eval_dataset");
        }
    }

    @Test
    // 此断言依赖生产 MySQL 的跨表一致性快照；H2 REPEATABLE_READ 仍可读到新插入行。
    @EnabledIfEnvironmentVariable(named = "KB_EVAL_TEST_JDBC_URL", matches = ".+")
    void snapshotMustReadTheRevisionAndCasesFromOneDatabaseView() throws Exception {
        CountDownLatch datasetRead = new CountDownLatch(1);
        CountDownLatch resumeSnapshot = new CountDownLatch(1);
        doAnswer(invocation -> {
            EvalDataset result = storedDatasets.selectOne(invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("eval-snapshot-reader")) {
                datasetRead.countDown();
                if (!resumeSnapshot.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot resume timed out");
            }
            return result;
        }).when(datasets).selectOne(any());
        var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "eval-snapshot-reader"));
        try {
            var future = executor.submit(() -> service.snapshotForRun(DATASET));
            assertTrue(datasetRead.await(5, TimeUnit.SECONDS));
            assertEquals("saved", create("稍后提交的问题"));
            resumeSnapshot.countDown();
            var captured = future.get(10, TimeUnit.SECONDS);
            assertEquals(0, captured.dataset().getDatasetRevision());
            assertEquals(0, captured.cases().size());
            assertEquals(1, service.snapshotForRun(DATASET).cases().size());
        } finally { resumeSnapshot.countDown(); executor.shutdownNow(); }
    }

    @Test
    void snapshotMustRetainCapturedEvidenceAndStatusAfterTheCaseIsDeprecated() {
        assertEquals("saved", create("最初的问题"));
        var captured = service.snapshotForRun(DATASET);
        var input = captured.cases().get(0);
        service.recheck(input.caseId(), EvalRecheckAction.DEPRECATE, List.of());
        assertEquals("最初的问题", input.query());
        assertTrue(input.evidences().contains("dv_fixture"));
        assertEquals(CaseStatus.ACTIVE, input.status());
        assertEquals(1, captured.dataset().getDatasetRevision());
        assertEquals(2, service.snapshotForRun(DATASET).dataset().getDatasetRevision());
        assertTrue(service.snapshotForRun(DATASET).cases().isEmpty());
    }

    @Test
    void staleDeleteMustNotDeleteAChangedCaseOrDecrementTheNewCount() {
        assertEquals("saved", create("已有的问题"));
        EvalCase stale = storedCases.selectList(null).get(0);
        // 模拟另一笔已经提交的废弃操作，再让当前请求持有旧的用例视图。
        service.recheck(stale.getCaseId(), EvalRecheckAction.DEPRECATE, List.of());
        doReturn(stale).when(cases).selectOne(any());
        BizException failure = assertThrows(BizException.class, () -> service.deleteCase(stale.getCaseId()));
        assertEquals("EVAL_DATASET_CONFLICT", failure.getErrorCode().name());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_eval_case WHERE deleted=0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT case_count FROM t_kb_eval_dataset", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT dataset_revision FROM t_kb_eval_dataset", Integer.class));
    }

    @Test
    void concurrentCaseCreationMustNotCommitAnUnversionedCase() throws Exception {
        CountDownLatch readBoth = new CountDownLatch(2);
        doAnswer(invocation -> {
            EvalDataset result = storedDatasets.selectOne(invocation.getArgument(0));
            readBoth.countDown();
            if (!readBoth.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent dataset reads timed out");
            return result;
        }).when(datasets).selectOne(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> create("第一个问题"));
            var second = executor.submit(() -> create("第二个问题"));
            var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            int rows = jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_eval_case WHERE deleted=0", Integer.class);
            assertEquals(rows, jdbc.queryForObject("SELECT case_count FROM t_kb_eval_dataset", Integer.class));
            assertEquals(rows, jdbc.queryForObject("SELECT dataset_revision FROM t_kb_eval_dataset", Integer.class));
            assertEquals(1, outcomes.stream().filter("saved"::equals).count());
            assertEquals(1, outcomes.stream().filter("EVAL_DATASET_CONFLICT"::equals).count());
        } finally { executor.shutdownNow(); }
    }

    private String create(String query) {
        EvalEvidence evidence = new EvalEvidence();
        evidence.setDocId("doc_fixture");
        try {
            service.createCase(DATASET, EvalCaseCommand.builder().query(query).anchorType(AnchorType.DOCUMENT)
                    .evidences(List.of(evidence)).build());
            return "saved";
        } catch (BizException failure) { return failure.getErrorCode().name(); }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfig { }
}
