package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用生产迁移、真实 Mapper 和事务代理验证并发与原子性，模型调用不参与本层测试。 */
class EmployeeConversationLedgerTest {
    private static final EmployeeConversationScope OWNER = new EmployeeConversationScope("tenant_a", "user_a", "app_a");
    private static final String WORKER = "worker_a";
    private static final String REFERENCES = "[{\"doc_id\":\"doc_a\",\"content\":\"来源原文\"}]";
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private EmployeeConversationLedger ledger;
    private EmployeeConversationRunMapper runs;
    private String conversationId;

    @BeforeEach
    void setUp() throws Exception {
        String mysqlUrl = System.getenv("KB_CONVERSATION_TEST_JDBC_URL");
        if (mysqlUrl != null && !mysqlUrl.matches("jdbc:mysql://[^/]+/codex_conversation_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Conversation integration tests require a dedicated codex_conversation_ schema");
        }
        var source = mysqlUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:conversations_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")
                : new DriverManagerDataSource(mysqlUrl, System.getenv("KB_CONVERSATION_TEST_USER"),
                    System.getenv("KB_CONVERSATION_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        String migration = Files.readString(Path.of("../kb-api/src/main/resources/db/migration/V28__employee_conversations.sql"));
        // 仅移除 H2 不支持的表级引擎与排序规则，字段、索引和约束继续来自生产迁移。
        String ddl = mysqlUrl == null ? migration.replaceAll("ENGINE = InnoDB[^;]+", "") : migration;
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new AuditFieldFiller()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(EmployeeConversationMapper.class);
        configuration.addMapper(EmployeeConversationRunMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        runs = session.getMapper(EmployeeConversationRunMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(EmployeeConversationLedger.class,
                () -> new EmployeeConversationLedger(session.getMapper(EmployeeConversationMapper.class), runs, new BizIdGenerator()));
        context.refresh();
        ledger = context.getBean(EmployeeConversationLedger.class);
        conversationId = ledger.create(OWNER, "制度问答").getConversationId();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        if (context != null) context.close();
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS t_kb_conversation_run");
            jdbc.execute("DROP TABLE IF EXISTS t_kb_conversation");
        }
    }

    @Test
    void shouldPersistQuestionAndOriginalTargetBeforeReturningAcceptance() {
        var accepted = accept("request_a");
        assertTrue(accepted.created());
        assertEquals("报销需要什么？", jdbc.queryForObject("SELECT question FROM t_kb_conversation_run", String.class));
        assertEquals(ConversationRunStatus.PENDING, accepted.run().getStatus());
        assertEquals(accepted.run().getRunId(), activeRun());

        var duplicate = ledger.accept(OWNER, conversationId, "request_a", "报销需要什么？", target("av_new"));
        assertFalse(duplicate.created());
        assertEquals(accepted.run().getRunId(), duplicate.run().getRunId());
        assertEquals("av_original", JsonUtil.parse(duplicate.run().getTargetJson(), EmployeeRunTarget.class).appVersionId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_conversation_run", Integer.class));
        assertEquals(ErrorCode.CONVERSATION_REQUEST_CONFLICT,
                assertThrows(BizException.class, () -> ledger.accept(OWNER, conversationId, "request_a", "改过的问题", target("av_new")))
                        .getErrorCode());
    }

    @Test
    void shouldAcceptConcurrentDuplicateExactlyOnce() throws Exception {
        var results = concurrent(() -> accept("request_same"), () -> accept("request_same"));
        assertEquals(1, results.stream().filter(EmployeeConversationLedger.AcceptedRun::created).count());
        assertEquals(results.get(0).run().getRunId(), results.get(1).run().getRunId());
        assertEquals(1, jdbc.queryForObject("SELECT last_turn FROM t_kb_conversation", Integer.class));
    }

    @Test
    void shouldAllowOnlyOneOfTwoTabsToStartDifferentQuestions() throws Exception {
        var results = concurrent(() -> acceptOrCode("request_a"), () -> acceptOrCode("request_b"));
        assertEquals(1, results.stream().filter("created"::equals).count());
        assertEquals(1, results.stream().filter(ErrorCode.CONVERSATION_BUSY.name()::equals).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_conversation_run", Integer.class));
    }

    @Test
    void shouldRejectDuplicateClaimsAndSaveEvidenceBeforeAnyAnswer() {
        String run = accept("request_a").run().getRunId();
        assertTrue(ledger.start(OWNER, conversationId, run, WORKER));
        assertFalse(ledger.start(OWNER, conversationId, run, WORKER));
        assertFalse(ledger.start(OWNER, conversationId, run, "worker_other"));
        assertFalse(ledger.checkpoint(OWNER, conversationId, run, WORKER, 1, "过早的正文"));
        assertFalse(ledger.succeed(OWNER, conversationId, run, WORKER, 1, "过早完成"));
        assertFalse(ledger.retrieved(OWNER, conversationId, run, "worker_other", REFERENCES, "{}", false));
        assertTrue(ledger.retrieved(OWNER, conversationId, run, WORKER, REFERENCES, "{\"mode\":\"FROZEN\"}", false));
        assertTrue(ledger.checkpoint(OWNER, conversationId, run, WORKER, 2, "当前回答"));
        assertFalse(ledger.checkpoint(OWNER, conversationId, run, WORKER, 1, "旧回答"));
        assertEquals("当前回答", get(run).getAnswer());
        assertTrue(ledger.succeed(OWNER, conversationId, run, WORKER, 3, "完整回答"));
        var saved = get(run);
        assertEquals(ConversationRunStatus.SUCCEEDED, saved.getStatus());
        assertEquals("完整回答", saved.getAnswer());
        assertEquals(REFERENCES, saved.getReferencesJson());
        assertNotNull(saved.getFinishedAt());
        assertNull(activeRun());
    }

    @Test
    void shouldKeepCancelledPartialAnswerAndIgnoreEveryLateCallback() {
        String run = generatingRun();
        assertTrue(ledger.checkpoint(OWNER, conversationId, run, WORKER, 1, "保留的半句"));
        assertTrue(ledger.cancel(OWNER, conversationId, run));
        assertFalse(ledger.succeed(OWNER, conversationId, run, WORKER, 2, "晚到完整答案"));
        assertFalse(ledger.fail(OWNER, conversationId, run, WORKER, "UPSTREAM_MODEL_ERROR", "迟到错误"));
        assertFalse(ledger.heartbeat(OWNER, conversationId, run, WORKER));
        assertFalse(ledger.retrieved(OWNER, conversationId, run, WORKER, "[]", "{}", true));
        assertFalse(ledger.cancel(OWNER, conversationId, run));
        assertEquals(ConversationRunStatus.CANCELLED, get(run).getStatus());
        assertEquals("保留的半句", get(run).getAnswer());
        var retry = accept("request_retry");
        assertNotEquals(run, retry.run().getRunId());
        assertEquals(2, retry.run().getTurnNo());
        assertFalse(ledger.succeed(OWNER, conversationId, run, WORKER, 3, "又迟到"));
        assertEquals(retry.run().getRunId(), activeRun());
        assertFalse(accept("request_a").created());
    }

    @Test
    void shouldSerializeCompletionAgainstExplicitStop() throws Exception {
        String run = generatingRun();
        var results = concurrent(() -> ledger.cancel(OWNER, conversationId, run),
                () -> ledger.succeed(OWNER, conversationId, run, WORKER, 1, "最终答案"));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        var saved = get(run);
        assertTrue(Set.of(ConversationRunStatus.SUCCEEDED, ConversationRunStatus.CANCELLED).contains(saved.getStatus()));
        assertEquals(saved.getStatus() == ConversationRunStatus.SUCCEEDED ? "最终答案" : "", saved.getAnswer());
        assertNull(activeRun());
    }

    @Test
    void shouldRollBackAcceptanceIfConversationPositionCannotBeSaved() {
        jdbc.execute("ALTER TABLE t_kb_conversation ADD CONSTRAINT fail_position CHECK(last_turn = 0)");
        assertThrows(RuntimeException.class, () -> accept("request_a"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_conversation_run", Integer.class));
        assertNull(activeRun());
    }

    @Test
    void shouldRollBackAnswerAndTerminalWhenReleasingPositionFails() {
        String run = generatingRun();
        jdbc.execute("ALTER TABLE t_kb_conversation ADD CONSTRAINT fail_release CHECK(active_run_id IS NOT NULL)");
        assertThrows(RuntimeException.class, () -> ledger.succeed(OWNER, conversationId, run, WORKER, 1, "不会假成功"));
        var saved = get(run);
        assertEquals(ConversationRunStatus.RUNNING, saved.getStatus());
        assertEquals("", saved.getAnswer());
        assertNull(saved.getFinishedAt());
        assertEquals(run, activeRun());
    }

    @Test
    void shouldFenceTenantUserApplicationAndDeletedRootsWithoutRequestContext() {
        String run = accept("request_a").run().getRunId();
        for (var forbidden : List.of(new EmployeeConversationScope("tenant_b", "user_a", "app_a"),
                new EmployeeConversationScope("tenant_a", "user_b", "app_a"),
                new EmployeeConversationScope("tenant_a", "user_a", "app_b"))) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                    () -> ledger.get(forbidden, conversationId, run)).getErrorCode());
            assertThrows(BizException.class, () -> ledger.cancel(forbidden, conversationId, run));
            assertThrows(BizException.class, () -> ledger.accept(forbidden, conversationId, "x", "x", target("av_original")));
        }
        String otherConversation = ledger.create(OWNER, "另一个会话").getConversationId();
        assertThrows(BizException.class, () -> ledger.get(OWNER, otherConversation, run));
        jdbc.update("UPDATE t_kb_conversation SET deleted=1 WHERE conversation_id=?", conversationId);
        assertThrows(BizException.class, () -> get(run));
        assertThrows(BizException.class, () -> ledger.cancel(OWNER, conversationId, run));
        assertEquals(ConversationRunStatus.PENDING.name(), jdbc.queryForObject("SELECT status FROM t_kb_conversation_run", String.class));
    }

    @Test
    void shouldAddTenantPluginFenceEvenForPlatformOperator() {
        String run = accept("request_a").run().getRunId();
        UserContextHolder.set(new UserPrincipal("user_b", "tenant_b", "other", "Other", UserSource.LOCAL,
                Set.of(), Set.of(), Set.of("tenant:manage"), true, Set.of()));
        assertThrows(BizException.class, () -> get(run));
        assertThrows(BizException.class, () -> ledger.cancel(OWNER, conversationId, run));
    }

    @Test
    void shouldRecheckHeartbeatAfterStaleScanAndNeverReviveInterruptedRun() {
        String run = generatingRun();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE t_kb_conversation_run SET updated_at=? WHERE run_id=?", cutoff.minusMinutes(1), run);
        var candidates = runs.staleAddresses(cutoff, 10);
        assertEquals(1, candidates.size());
        assertEquals(OWNER, candidates.get(0).scope());
        assertTrue(ledger.heartbeat(OWNER, conversationId, run, WORKER));
        assertFalse(ledger.interruptIfStale(OWNER, conversationId, run, cutoff));
        jdbc.update("UPDATE t_kb_conversation_run SET updated_at=? WHERE run_id=?", cutoff.minusMinutes(1), run);
        assertTrue(ledger.interruptIfStale(OWNER, conversationId, run, cutoff));
        assertEquals(ConversationRunStatus.INTERRUPTED, get(run).getStatus());
        assertFalse(ledger.start(OWNER, conversationId, run, WORKER));
        assertFalse(ledger.succeed(OWNER, conversationId, run, WORKER, 1, "晚到"));
        assertTrue(runs.staleAddresses(LocalDateTime.now().plusMinutes(1), 10).isEmpty());
        assertNull(activeRun());
    }

    @Test
    void shouldReleaseRejectedQueuePositionAndPreserveTerminalIdempotence() {
        String run = accept("request_a").run().getRunId();
        assertTrue(ledger.reject(OWNER, conversationId, run, "BUSY", "当前繁忙，请稍后重试"));
        assertFalse(ledger.start(OWNER, conversationId, run, WORKER));
        assertEquals(ConversationRunStatus.FAILED, get(run).getStatus());
        assertEquals("BUSY", get(run).getErrorCode());
        assertNull(activeRun());
        assertFalse(accept("request_a").created());
        assertTrue(accept("request_retry").created());
    }

    @Test
    void shouldCapturePublishedConfigurationIndependentlyFromLaterReleaseState() {
        AppVersion version = new AppVersion();
        version.setAppId("app_a");
        version.setAppVersionId("av_original");
        version.setVersion("V1.0");
        version.setConfig("{\"prompt\":\"原始提示\"}");
        version.setIndexSnapshots("[{\"kb_id\":\"kb_a\"}]");
        version.setVisibleVersionIds("{\"kb_a\":[\"dv_a\"]}");
        version.setStatus(AppVersionStatus.RELEASED);
        var target = EmployeeRunTarget.capture(version);
        assertTrue(target.snapshotBound());
        version.setStatus(AppVersionStatus.SUPERSEDED);
        version.setIndexSnapshots(null);
        version.setVisibleVersionIds(null);
        version.setConfig("{}");
        assertEquals("{\"prompt\":\"原始提示\"}", target.config());
        assertNotNull(target.indexSnapshots());
        assertThrows(BizException.class, () -> EmployeeRunTarget.capture(version));
    }

    private EmployeeConversationLedger.AcceptedRun accept(String request) {
        return ledger.accept(OWNER, conversationId, request, "报销需要什么？", target("av_original"));
    }

    private String acceptOrCode(String request) {
        try {
            accept(request);
            return "created";
        } catch (BizException error) {
            return error.getErrorCode().name();
        }
    }

    private String generatingRun() {
        String run = accept("request_a").run().getRunId();
        assertTrue(ledger.start(OWNER, conversationId, run, WORKER));
        assertTrue(ledger.retrieved(OWNER, conversationId, run, WORKER, REFERENCES, "{}", false));
        return run;
    }

    private EmployeeConversationRun get(String run) {
        return ledger.get(OWNER, conversationId, run);
    }

    private String activeRun() {
        return jdbc.queryForObject("SELECT active_run_id FROM t_kb_conversation WHERE conversation_id=?", String.class, conversationId);
    }

    private EmployeeRunTarget target(String version) {
        return new EmployeeRunTarget("app_a", version, "V1.0", "{}", null, null, false);
    }

    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(operation -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout");
                return operation.call();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {
    }
}
