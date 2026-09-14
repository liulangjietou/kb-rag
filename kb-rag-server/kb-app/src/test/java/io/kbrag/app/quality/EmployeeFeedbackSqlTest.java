package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.mapper.EmployeeFeedbackMapper;
import io.kbrag.domain.model.EmployeeRunTarget;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 运行真实 MySQL JSON、JOIN 和生产分页插件；专用临时 schema 由外部验证脚本提供。 */
@EnabledIfEnvironmentVariable(named = "KB_EMPLOYEE_FEEDBACK_TEST_JDBC_URL", matches = "jdbc:mysql://.+")
class EmployeeFeedbackSqlTest {
    private JdbcTemplate jdbc;
    private EmployeeFeedbackMapper mapper;

    @BeforeEach
    void setUp() {
        String url = System.getenv("KB_EMPLOYEE_FEEDBACK_TEST_JDBC_URL");
        if (!url.matches("jdbc:mysql://[^/]+/codex_employee_feedback_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Employee feedback SQL tests require their own temporary schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("KB_EMPLOYEE_FEEDBACK_TEST_USER"),
                System.getenv("KB_EMPLOYEE_FEEDBACK_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE t_kb_app (app_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_kb_conversation (conversation_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), user_id VARCHAR(64), app_id VARCHAR(64), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_kb_conversation_run (id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id VARCHAR(64), conversation_id VARCHAR(64), tenant_id VARCHAR(64), user_id VARCHAR(64), app_id VARCHAR(64), target_json JSON, status VARCHAR(32), feedback_verdict VARCHAR(16), feedback_updated_at DATETIME(6), deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO t_kb_app(app_id, tenant_id) VALUES ('app','tenant'),('second','tenant'),('foreign','outside')");
        seed("first", "tenant", "app", "kb", "BAD"); seed("second", "tenant", "app", "kb", "BAD");
        seed("other_app", "tenant", "second", "kb", "BAD"); seed("other_kb", "tenant", "app", "outside", "BAD");
        seed("good", "tenant", "app", "kb", "GOOD"); seed("unrated", "tenant", "app", "kb", null);
        seed("other_tenant", "outside", "foreign", "kb", "BAD"); seed("wrong_app_tenant", "tenant", "foreign", "kb", "BAD");
        seed("deleted", "tenant", "app", "kb", "BAD");
        jdbc.update("UPDATE t_kb_conversation_run SET deleted=1 WHERE run_id='deleted'");
        seed("unfinished", "tenant", "app", "kb", "BAD");
        jdbc.update("UPDATE t_kb_conversation_run SET status='FAILED' WHERE run_id='unfinished'");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        config.addMapper(EmployeeFeedbackMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(EmployeeFeedbackMapper.class);
    }

    @AfterEach
    void clear() {
        if (jdbc == null) return;
        for (String table : List.of("t_kb_conversation_run", "t_kb_conversation", "t_kb_app")) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void scopeAndFrozenKbMembershipApplyToCountBeforePagination() {
        var first = mapper.pageBad(new Page<>(1, 1), "tenant", "kb", List.of("app"));
        assertEquals(2, first.getTotal()); assertEquals("second", first.getRecords().get(0).getRunId());
        var next = mapper.pageBad(new Page<>(2, 1), "tenant", "kb", List.of("app"));
        assertEquals(2, next.getTotal()); assertEquals("first", next.getRecords().get(0).getRunId());
        assertEquals(0, mapper.pageBad(new Page<>(1, 20), "tenant", "kb", List.of()).getTotal());
        assertEquals(3, mapper.pageBad(new Page<>(1, 20), "tenant", "kb", null).getTotal());
    }

    @Test
    void deletedConversationAndApplicationAreRemovedFromBothRowsAndCount() {
        jdbc.update("UPDATE t_kb_conversation SET deleted=1 WHERE conversation_id='conv_second'");
        assertEquals(1, mapper.pageBad(new Page<>(1, 20), "tenant", "kb", List.of("app")).getTotal());
        jdbc.update("UPDATE t_kb_app SET deleted=1 WHERE app_id='app'");
        assertEquals(0, mapper.pageBad(new Page<>(1, 20), "tenant", "kb", List.of("app")).getTotal());
    }

    private void seed(String id, String tenant, String app, String kb, String verdict) {
        var target = new EmployeeRunTarget(app, "version", "v1", "{\"kb_refs\":[{\"kb_id\":\"" + kb + "\",\"weight\":1}]}", null, null, true);
        jdbc.update("INSERT INTO t_kb_conversation(conversation_id,tenant_id,user_id,app_id) VALUES (?,?,?,?)", "conv_" + id, tenant, "employee", app);
        jdbc.update("INSERT INTO t_kb_conversation_run(run_id,conversation_id,tenant_id,user_id,app_id,target_json,status,feedback_verdict,feedback_updated_at) VALUES (?,?,?,?,?,?,'SUCCEEDED',?,'2026-09-14 12:00:00')",
                id, "conv_" + id, tenant, "employee", app, JsonUtil.toJson(target), verdict);
    }
}
