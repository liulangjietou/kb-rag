package io.kbrag.api.sse;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.api.advice.GlobalExceptionHandler;
import io.kbrag.api.controller.EmployeeConversationController;
import io.kbrag.api.filter.PermissionInterceptor;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.app.openapi.KnowledgeCallResult;
import io.kbrag.app.workspace.EmployeeConversationHistory;
import io.kbrag.app.workspace.EmployeeConversationLedger;
import io.kbrag.app.workspace.EmployeeConversationService;
import io.kbrag.app.workspace.EmployeeEvidenceService;
import io.kbrag.app.workspace.EmployeeRunCoordinator;
import io.kbrag.app.workspace.EmployeeWorkspaceAccess;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.mapper.EmployeeConversationMapper;
import io.kbrag.domain.mapper.EmployeeConversationRunMapper;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import jakarta.servlet.Filter;
import jakarta.servlet.DispatcherType;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 TCP、MVC、事务 Mapper 和执行线程；身份解析与检索模型使用可控夹具，无外部模型费用。 */
class EmployeeRunHttpIntegrationTest {
    private static final EmployeeConversationScope SCOPE = new EmployeeConversationScope("tenant", "user", "app");
    private static final UserPrincipal PRINCIPAL = new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of(), Set.of("app:use"), true, Set.of(), true, Set.of());
    private static final String ROOT = "/api/v1/workspace/apps/app/conversations";

    @Test
    void shouldContinueAfterTcpResetAndReconnectToTheSameCommittedAnswer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String conversation = fixture.create();
            String run = fixture.submit(conversation);
            assertTrue(fixture.started.await(5, TimeUnit.SECONDS));
            assertEquals(run, fixture.submit(conversation));
            var busy = fixture.request("POST", ROOT + "/" + conversation + "/runs",
                    "{\"request_id\":\"request_other\",\"query\":\"另一个问题\"}");
            assertEquals(409, busy.statusCode());
            try (Socket browser = fixture.subscribe(conversation, run)) {
                readUntil(browser, "partial answer");
                browser.setSoLinger(true, 0);
            }
            await().atMost(Duration.ofSeconds(8)).until(() -> fixture.subscriptionCount() == 0);
            assertFalse(fixture.cancellation.get().isCancelled());
            assertEquals("RUNNING", fixture.run(conversation, run).path("status").asText());
            try (Socket browser = fixture.subscribe(conversation, run)) {
                readUntil(browser, "partial answer");
                fixture.release.countDown();
                String remaining = readUntil(browser, "event:done");
                assertTrue(remaining.contains("SUCCEEDED"));
            }
            JsonNode saved = fixture.run(conversation, run);
            assertEquals("partial answer complete", saved.path("answer").asText());
            assertEquals("av_original", saved.path("app_version_id").asText());
            assertEquals("doc", saved.path("references").get(0).path("doc_id").asText());
            assertEquals(1, fixture.modelCalls.get());
            assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_conversation_run", Integer.class));
            fixture.readable.set(false);
            JsonNode restricted = fixture.run(conversation, run);
            assertTrue(restricted.path("restricted").asBoolean());
            assertEquals("", restricted.path("answer").asText());
            assertTrue(restricted.path("references").isEmpty());
        }
    }

    @Test
    void shouldPersistExplicitStopBeforeCancellingUpstreamAndKeepPartialAnswer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String conversation = fixture.create();
            String run = fixture.submit(conversation);
            try (Socket browser = fixture.subscribe(conversation, run)) {
                readUntil(browser, "partial answer");
                JsonNode stopped = fixture.data(fixture.request("POST", ROOT + "/" + conversation + "/runs/" + run + "/stop", ""));
                assertEquals("CANCELLED", stopped.path("status").asText());
                assertTrue(fixture.cancelled.await(5, TimeUnit.SECONDS));
                assertEquals("CANCELLED", fixture.statusAtCancellation.get());
                readUntil(browser, "event:done");
            }
            assertEquals("partial answer", fixture.run(conversation, run).path("answer").asText());
            assertEquals(run, fixture.submit(conversation));
            assertEquals(1, fixture.modelCalls.get());
        }
    }

    @Test
    void shouldNeverReportSuccessWhenTerminalTransactionCannotCommit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String conversation = fixture.create();
            String run = fixture.submit(conversation);
            try (Socket browser = fixture.subscribe(conversation, run)) {
                readUntil(browser, "partial answer");
                fixture.jdbc.execute("ALTER TABLE t_kb_conversation ADD CONSTRAINT fail_finish CHECK(active_run_id IS NOT NULL)");
                fixture.release.countDown();
                await().atMost(Duration.ofSeconds(5)).until(() -> fixture.activeRuns() == 0);
                assertEquals("RUNNING", fixture.run(conversation, run).path("status").asText());
                assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_conversation_run WHERE status='SUCCEEDED'", Integer.class));
                fixture.jdbc.execute("ALTER TABLE t_kb_conversation DROP CONSTRAINT fail_finish");
                fixture.jdbc.update("UPDATE t_kb_conversation_run SET updated_at = ? WHERE run_id = ?",
                        java.time.LocalDateTime.now().minusMinutes(5), run);
                assertTrue(fixture.ledger.interruptIfStale(SCOPE, conversation, run, java.time.LocalDateTime.now().minusMinutes(3)));
                String remaining = readUntil(browser, "event:done");
                assertTrue(remaining.contains("INTERRUPTED"));
                assertFalse(remaining.contains("SUCCEEDED"));
            }
        }
    }

    /** 读取到事件边界为止，避免缓冲读越过下一阶段；Socket 本身提供超时。 */
    private static String readUntil(Socket socket, String marker) throws Exception {
        StringBuilder received = new StringBuilder();
        // 每次只消费到指定行，不使用会预读后续事件的 BufferedReader。
        while (received.indexOf(marker) < 0 || received.charAt(received.length() - 1) != '\n') {
            int next = socket.getInputStream().read();
            assertTrue(next >= 0, "事件到达前连接不应结束：" + marker);
            received.append((char) next);
            assertTrue(received.length() < 1_000_000, "读取事件超出测试内容上限");
        }
        return received.toString();
    }

    private static final class Fixture implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<ChatCancellation> cancellation = new AtomicReference<>();
        private final AtomicReference<String> statusAtCancellation = new AtomicReference<>();
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicBoolean readable = new AtomicBoolean(true);
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final AnnotationConfigApplicationContext persistence = new AnnotationConfigApplicationContext();
        private final AnnotationConfigServletWebServerApplicationContext web = new AnnotationConfigServletWebServerApplicationContext();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final JdbcTemplate jdbc;
        private final EmployeeConversationLedger ledger;
        private final EmployeeRunCoordinator coordinator;
        private final EmployeeRunSubscriptions subscriptions;
        private final Path tomcatDirectory;

        private Fixture() throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:employee_http_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            jdbc = new JdbcTemplate(source);
            String ddl = Files.readString(Path.of("src/main/resources/db/migration/V28__employee_conversations.sql"))
                    .replaceAll("ENGINE = InnoDB[^;]+", "")
                    + Files.readString(Path.of("src/main/resources/db/migration/V29__employee_answer_feedback.sql"));
            for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                    .setMetaObjectHandler(new AuditFieldFiller()));
            configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
            configuration.addMapper(EmployeeConversationMapper.class);
            configuration.addMapper(EmployeeConversationRunMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            var runs = session.getMapper(EmployeeConversationRunMapper.class);
            persistence.register(TransactionConfiguration.class);
            persistence.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            persistence.registerBean(EmployeeConversationLedger.class, () -> new EmployeeConversationLedger(
                    session.getMapper(EmployeeConversationMapper.class), runs, new BizIdGenerator()));
            persistence.refresh();
            ledger = persistence.getBean(EmployeeConversationLedger.class);
            var access = mock(EmployeeWorkspaceAccess.class);
            when(access.current()).thenReturn(PRINCIPAL);
            when(access.refresh(PRINCIPAL)).thenReturn(PRINCIPAL);
            when(access.scope(PRINCIPAL, "app")).thenReturn(SCOPE);
            when(access.releasedTarget(PRINCIPAL, "app"))
                    .thenReturn(new EmployeeRunTarget("app", "av_original", "v1", "{}", null, null, false));
            var evidence = mock(EmployeeEvidenceService.class);
            when(evidence.canReadAll(any(), anyList())).thenAnswer(call -> readable.get());
            when(evidence.capture(any(), anyList())).thenReturn(List.of(new EmployeeCitation("doc", "version", "chunk", "kb",
                    "材料", "v1", null, null, null, null, 1, "原文", false)));
            var history = new EmployeeConversationHistory(ledger, evidence);
            var knowledge = mock(KnowledgeApiService.class);
            doAnswer(call -> {
                modelCalls.incrementAndGet();
                call.<Consumer<KnowledgeCallResult>>getArgument(2).accept(KnowledgeCallResult.builder().nodes(List.of()).degraded(List.of()).build());
                Consumer<String> delta = call.getArgument(3);
                ChatCancellation signal = call.getArgument(4);
                cancellation.set(signal);
                signal.onCancel(() -> {
                    statusAtCancellation.set(jdbc.queryForObject("SELECT status FROM t_kb_conversation_run", String.class));
                    cancelled.countDown();
                    release.countDown();
                });
                delta.accept("partial answer");
                started.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS));
                signal.throwIfCancelled();
                delta.accept(" complete");
                return null;
            }).when(knowledge).employeeStream(any(), any(), any(), any(), any());
            coordinator = new EmployeeRunCoordinator(ledger, access, history, evidence, knowledge, runs, worker);
            var conversations = new EmployeeConversationService(access, ledger, history, coordinator);
            subscriptions = new EmployeeRunSubscriptions(conversations, access);
            tomcatDirectory = Files.createTempDirectory("kb-employee-http-");
            web.register(WebConfiguration.class);
            web.registerBean(TomcatServletWebServerFactory.class, () -> {
                var factory = new TomcatServletWebServerFactory(0);
                factory.setBaseDirectory(tomcatDirectory.toFile());
                return factory;
            });
            web.registerBean(EmployeeConversationController.class, () -> new EmployeeConversationController(conversations, subscriptions));
            web.registerBean(GlobalExceptionHandler.class);
            web.refresh();
        }

        private String create() throws Exception {
            return data(request("POST", ROOT, "{\"title\":\"流程验证\"}")).path("conversation_id").asText();
        }

        private String submit(String conversation) throws Exception {
            return data(request("POST", ROOT + "/" + conversation + "/runs",
                    "{\"request_id\":\"request_1\",\"query\":\"问题\"}")).path("run_id").asText();
        }

        private JsonNode run(String conversation, String run) throws Exception {
            return data(request("GET", ROOT + "/" + conversation + "/runs/" + run, ""));
        }

        private HttpResponse<String> request(String method, String path, String body) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private JsonNode data(HttpResponse<String> response) {
            assertEquals(200, response.statusCode(), response.body());
            return JsonUtil.parse(response.body(), JsonNode.class).path("data");
        }

        private Socket subscribe(String conversation, String run) throws Exception {
            Socket browser = new Socket("127.0.0.1", web.getWebServer().getPort());
            browser.setSoTimeout(10_000);
            browser.getOutputStream().write(("GET " + ROOT + "/" + conversation + "/runs/" + run
                    + "/events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            browser.getOutputStream().flush();
            return browser;
        }

        private int subscriptionCount() {
            synchronized (subscriptions) {
                return ((Set<?>) ReflectionTestUtils.getField(subscriptions, "active")).size();
            }
        }

        private int activeRuns() {
            return ((java.util.Map<?, ?>) ReflectionTestUtils.getField(coordinator, "active")).size();
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            subscriptions.close();
            coordinator.close();
            web.close();
            persistence.close();
            jdbc.execute("SHUTDOWN");
            try (var files = Files.walk(tomcatDirectory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration { }

    @Configuration
    @EnableWebMvc
    static class WebConfiguration implements WebMvcConfigurer {
        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcher(WebApplicationContext context) {
            var servlet = new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
            servlet.setAsyncSupported(true);
            return servlet;
        }

        @Bean
        FilterRegistrationBean<Filter> fixtureIdentity() {
            Filter filter = (request, response, chain) -> {
                UserContextHolder.set(PRINCIPAL);
                try { chain.doFilter(request, response); }
                finally { UserContextHolder.clear(); }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            return registration;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new PermissionInterceptor());
        }
    }
}
