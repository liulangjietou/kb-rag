package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.eval.EvalCaseCommand;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.QualityIssueReason;
import io.kbrag.domain.enums.QualityIssueSource;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.mapper.KnowledgeQualityIssueMapper;
import io.kbrag.domain.mapper.QualityIssueRecordMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import io.kbrag.domain.service.TextDesensitizer;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 生产 Mapper、乐观锁和 Spring 事务共同验证状态、记录与用例写入的原子性。 */
class QualityIssueTransactionTest {
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private KnowledgeQualityIssueService service;
    private EvalDatasetService datasets;
    private KnowledgeQualityIssueMapper issues;
    private KnowledgeQualityIssueMapper storedIssues;

    @BeforeEach
    void setUp() throws Exception {
        String mysqlUrl = System.getenv("KB_QUALITY_TEST_JDBC_URL");
        if (mysqlUrl != null && !mysqlUrl.matches("jdbc:mysql://[^/]+/codex_quality_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Quality tests require a dedicated codex_quality_ schema");
        }
        var source = mysqlUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:quality_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")
                : new DriverManagerDataSource(mysqlUrl, System.getenv("KB_QUALITY_TEST_USER"), System.getenv("KB_QUALITY_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        Path migrations = Path.of("../kb-api/src/main/resources/db/migration");
        for (String name : List.of("V5__evaluation.sql", "V17__tenant_doc_acl_audit.sql", "V23__final_answer_evaluation.sql", "V31__knowledge_quality_issues.sql")) {
            String ddl = Files.readString(migrations.resolve(name)).replaceAll("(?m)^\\s*--.*$", "");
            for (String statement : ddl.split(";")) {
                if (!statement.matches("(?s)\\s*(CREATE TABLE|ALTER TABLE) t_kb_(eval_(dataset|case)|quality_issue(_record)?)\\b.*")) continue;
                String sql = mysqlUrl == null ? statement.replaceAll("(?s)ENGINE = InnoDB.*", "")
                        .replaceAll("\\bJSON\\b", "LONGTEXT").replaceAll(",\\s*ADD KEY idx_tenant \\(tenant_id\\)", "") : statement;
                jdbc.execute(sql);
            }
        }
        jdbc.execute("CREATE TABLE t_kb_knowledge_base (kb_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO t_kb_knowledge_base (kb_id,tenant_id) VALUES (?,?)", "kb_safe", "tenant_safe");
        jdbc.update("INSERT INTO t_kb_knowledge_base (kb_id,tenant_id) VALUES (?,?)", "kb_other", "tenant_other");
        jdbc.update("INSERT INTO t_kb_eval_dataset (dataset_id,kb_id,name,tenant_id) VALUES (?,?,?,?)", "ds_safe", "kb_safe", "质量验证", "tenant_safe");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new AuditFieldFiller()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        for (Class<?> mapper : List.of(KnowledgeQualityIssueMapper.class, QualityIssueRecordMapper.class,
                KnowledgeBaseMapper.class, EvalDatasetMapper.class, EvalCaseMapper.class)) configuration.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        storedIssues = session.getMapper(KnowledgeQualityIssueMapper.class);
        issues = mock(KnowledgeQualityIssueMapper.class, delegatesTo(storedIssues));
        var cases = session.getMapper(EvalCaseMapper.class);
        var datasetMapper = session.getMapper(EvalDatasetMapper.class);
        var documents = mock(DocumentMapper.class);
        var chunks = mock(ChunkMapper.class);
        var apps = mock(AppMapper.class);
        var versions = mock(AppVersionService.class);
        var version = new AppVersion(); version.setAppId("app_safe");
        when(versions.require("av_safe")).thenReturn(version);
        var snapshot = new AppConfigSnapshot(); snapshot.setKbRefs(List.of(KbRef.of("kb_safe")));
        when(versions.parseConfig(version)).thenReturn(snapshot);
        var app = new App(); app.setAppId("app_safe"); app.setTenantId("tenant_safe");
        when(apps.selectOne(any())).thenReturn(app);
        var access = new QualityIssueAccess(session.getMapper(KnowledgeBaseMapper.class), documents, chunks, cases,
                apps, mock(KbResourceGuard.class), versions);
        var insights = mock(SearchInsightMapper.class);
        var insight = new SearchInsight(); insight.setQueryDigest("脱敏的问题摘要");
        insight.setQueryHash("hash_safe");
        when(insights.selectOne(any())).thenReturn(insight);
        context = new AnnotationConfigApplicationContext(); context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(EvalDatasetService.class, () -> new EvalDatasetService(datasetMapper, cases,
                mock(EvalRunMapper.class), mock(EvalResultMapper.class), documents, chunks,
                mock(KnowledgeBaseService.class), new BizIdGenerator()));
        context.registerBean(KnowledgeQualityIssueService.class, () -> new KnowledgeQualityIssueService(issues,
                session.getMapper(QualityIssueRecordMapper.class), mock(RetrievalFeedbackMapper.class), insights,
                context.getBean(EvalDatasetService.class), access, mock(QualityRegressionVerifier.class),
                new QueryDigestFactory(new TextDesensitizer())));
        context.refresh();
        service = context.getBean(KnowledgeQualityIssueService.class); datasets = context.getBean(EvalDatasetService.class);
        principal("user_safe");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        if (context != null) context.close();
        if (jdbc != null) for (String table : List.of("t_kb_quality_issue_record", "t_kb_quality_issue",
                "t_kb_eval_case", "t_kb_eval_dataset", "t_kb_knowledge_base")) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void issueConflictRollsBackTheNewCaseAndItsDatasetRevision() {
        var issue = createdAndClaimed();
        doReturn(0).when(issues).updateById(any(KnowledgeQualityIssue.class));
        assertThrows(BizException.class, () -> service.correct("kb_safe", issue.getIssueId(), command(issue.getLockVersion(), null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_eval_case", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT dataset_revision FROM t_kb_eval_dataset", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue_record", Integer.class));
        assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM t_kb_quality_issue", String.class));
    }

    @Test
    void claimingAdvancesUpdateTimeWithoutRewritingCreationTime() {
        var issue = service.create("kb_safe", QualityIssueSource.ZERO_HIT, "audit_source");
        jdbc.update("UPDATE t_kb_quality_issue SET created_at='2020-01-01 00:00:00',updated_at='2020-01-01 00:00:00'");
        service.claim("kb_safe", issue.getIssueId(), issue.getLockVersion());
        var timestamps = jdbc.queryForObject("SELECT created_at,updated_at FROM t_kb_quality_issue",
                (row, index) -> List.of(row.getTimestamp("created_at").toLocalDateTime(), row.getTimestamp("updated_at").toLocalDateTime()));
        var originalTime = java.time.LocalDateTime.of(2020, 1, 1, 0, 0);
        assertEquals(originalTime, timestamps.get(0));
        assertTrue(timestamps.get(1).isAfter(originalTime));
    }

    @Test
    void duplicateSourceRemainsSingleAndOtherTenantCannotReachItsContent() {
        var issue = service.create("kb_safe", QualityIssueSource.ZERO_HIT, "hash_safe");
        assertEquals(issue.getIssueId(), service.create("kb_safe", QualityIssueSource.ZERO_HIT, "hash_safe").getIssueId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue_record", Integer.class));
        assertThrows(BizException.class, () -> service.detail("kb_other", issue.getIssueId()));
        assertThrows(BizException.class, () -> service.list("kb_other", null, false, 1, 20));
    }

    @Test
    void actualCaseRevisionRejectsStaleCorrectionAndPreservesExternalEdits() {
        var issue = createdAndClaimed();
        var corrected = service.correct("kb_safe", issue.getIssueId(), command(issue.getLockVersion(), null));
        var detail = service.detail("kb_safe", issue.getIssueId());
        int caseRevision = detail.currentCase().getLockVersion();
        datasets.updateCase(corrected.getCaseId(), input("评测页面的新问题"));
        assertThrows(BizException.class, () -> service.correct("kb_safe", issue.getIssueId(), command(corrected.getLockVersion(), caseRevision)));
        assertEquals("评测页面的新问题", jdbc.queryForObject("SELECT query FROM t_kb_eval_case", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue_record", Integer.class));
    }

    @Test
    void twoSimultaneousClaimsProduceExactlyOneOwnerAndOneClaimRecord() throws Exception {
        var issue = service.create("kb_safe", QualityIssueSource.ZERO_HIT, "hash_race");
        CountDownLatch read = new CountDownLatch(2);
        doAnswer(invocation -> {
            KnowledgeQualityIssue value = storedIssues.selectOne(invocation.getArgument(0));
            read.countDown();
            if (!read.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Claim barrier timed out");
            return value;
        }).when(issues).selectOne(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> claim(issue.getIssueId(), "user_one"));
            var second = executor.submit(() -> claim(issue.getIssueId(), "user_two"));
            assertEquals(Set.of("saved", "QUALITY_ISSUE_CONFLICT"), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT lock_version FROM t_kb_quality_issue", Integer.class));
            assertNotNull(jdbc.queryForObject("SELECT owner_user_id FROM t_kb_quality_issue", String.class));
        } finally { executor.shutdownNow(); }
    }

    private String claim(String issueId, String user) {
        principal(user);
        try { service.claim("kb_safe", issueId, 0); return "saved"; }
        catch (BizException conflict) { return conflict.getErrorCode().name(); }
        finally { UserContextHolder.clear(); }
    }

    private KnowledgeQualityIssue createdAndClaimed() {
        var issue = service.create("kb_safe", QualityIssueSource.ZERO_HIT, "hash_safe");
        return service.claim("kb_safe", issue.getIssueId(), issue.getLockVersion());
    }

    private KnowledgeQualityIssueService.Correction command(int revision, Integer caseRevision) {
        return new KnowledgeQualityIssueService.Correction(revision, caseRevision, QualityIssueReason.MISSING_KNOWLEDGE,
                "ds_safe", "av_safe", input("资料以外的问题"));
    }

    private EvalCaseCommand input(String query) {
        return EvalCaseCommand.builder().query(query).expectedRefusal(true).anchorType(AnchorType.DOCUMENT)
                .evidences(List.of()).note("人工确认没有依据").build();
    }

    private void principal(String userId) {
        UserContextHolder.set(new UserPrincipal(userId, "tenant_safe", userId, "处理人", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of(PermissionCodes.FEEDBACK_MANAGE, PermissionCodes.EVAL_READ,
                PermissionCodes.EVAL_WRITE, PermissionCodes.APP_READ, PermissionCodes.TENANT_MANAGE), true, Set.of(), true, Set.of()));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig { }
}
