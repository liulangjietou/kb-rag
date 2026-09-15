package io.kbrag.app.modelusage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.AuditFieldFiller;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.config.KbTenantLineHandler;
import io.kbrag.domain.config.MybatisPlusConfig;
import io.kbrag.domain.mapper.ModelPriceMapper;
import io.kbrag.domain.mapper.ModelUsageMapper;
import io.kbrag.domain.mapper.ModelUsageMonthlyMapper;
import io.kbrag.domain.mapper.TenantMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 生产 Mapper、乐观锁和 Spring 事务验证预占回收的计量一致性。 */
class ModelUsageRecoveryTransactionTest {
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private ModelUsageService service;
    private final LocalDateTime expiredAt = LocalDateTime.now().minusHours(3);
    private final String month = expiredAt.format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @BeforeEach
    void setUp() throws Exception {
        String mysqlUrl = System.getenv("KB_USAGE_TEST_JDBC_URL");
        if (mysqlUrl != null && !mysqlUrl.matches("jdbc:mysql://[^/]+/codex_usage_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Usage recovery tests require a dedicated codex_usage_ schema");
        }
        var source = mysqlUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:usage_recovery_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(mysqlUrl, System.getenv("KB_USAGE_TEST_USER"), System.getenv("KB_USAGE_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        String ddl = Files.readString(Path.of("../kb-api/src/main/resources/db/migration/V24__model_usage_and_tenant_quota.sql"))
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : ddl.split(";")) {
            if (!statement.matches("(?s)\\s*CREATE TABLE t_kb_model_usage(_monthly)?\\b.*")) continue;
            String sql = mysqlUrl == null ? statement.replaceAll("(?s)ENGINE = InnoDB.*", "") : statement;
            if (mysqlUrl == null) {
                var table = java.util.regex.Pattern.compile("(?s)\\s*CREATE TABLE (\\w+)").matcher(sql);
                if (table.find()) sql = sql.replaceAll("\\bKEY (\\w+)", "KEY " + table.group(1) + "_$1");
            }
            jdbc.execute(sql);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new AuditFieldFiller()));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new KbTenantLineHandler()));
        configuration.addMapper(ModelUsageMapper.class);
        configuration.addMapper(ModelUsageMonthlyMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ModelUsageService.class, () -> new ModelUsageService(session.getMapper(ModelUsageMapper.class),
                session.getMapper(ModelUsageMonthlyMapper.class), mock(ModelPriceMapper.class), mock(TenantMapper.class),
                new BizIdGenerator(), new KbProperties()));
        context.refresh();
        service = context.getBean(ModelUsageService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (jdbc != null) for (String table : List.of("t_kb_model_usage", "t_kb_model_usage_monthly")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void aMissingMonthlyCounterRollsBackEveryLedgerChangeInTheBatch() {
        reserve("first", "tenant_a", expiredAt, 100, true);
        reserve("missing-counter", "tenant_b", expiredAt.plusMinutes(1), 200, false);

        assertThrows(BizException.class, () -> service.reconcileStaleReservations());

        assertAll(
                () -> assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_model_usage WHERE status='RESERVED'", Integer.class)),
                () -> assertEquals(0L, jdbc.queryForObject("SELECT used_tokens FROM t_kb_model_usage_monthly", Long.class)),
                () -> assertEquals(100L, jdbc.queryForObject("SELECT reserved_tokens FROM t_kb_model_usage_monthly", Long.class)));
    }

    @Test
    void expirySettlesOncePerTenantAndLeavesFreshAndDeletedRowsUntouched() {
        reserve("expired-a", "tenant_a", expiredAt, 100, true);
        reserve("expired-b", "tenant_b", expiredAt, 200, true);
        reserve("fresh", "tenant_c", LocalDateTime.now(), 300, true);
        reserve("deleted", "tenant_d", expiredAt, 400, true);
        jdbc.update("UPDATE t_kb_model_usage SET deleted=1 WHERE usage_id='deleted'");

        service.reconcileStaleReservations();
        service.reconcileStaleReservations();

        assertAll(
                () -> assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_model_usage WHERE status='FAILED' AND error_type='RESERVATION_EXPIRED' AND estimated=1", Integer.class)),
                () -> assertEquals(100L, jdbc.queryForObject("SELECT used_tokens FROM t_kb_model_usage_monthly WHERE tenant_id='tenant_a'", Long.class)),
                () -> assertEquals(200L, jdbc.queryForObject("SELECT used_tokens FROM t_kb_model_usage_monthly WHERE tenant_id='tenant_b'", Long.class)),
                () -> assertEquals(0L, jdbc.queryForObject("SELECT SUM(reserved_tokens) FROM t_kb_model_usage_monthly WHERE tenant_id IN ('tenant_a','tenant_b')", Long.class)),
                () -> assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_model_usage WHERE status='RESERVED'", Integer.class)),
                () -> assertEquals(700L, jdbc.queryForObject("SELECT SUM(reserved_tokens) FROM t_kb_model_usage_monthly WHERE tenant_id IN ('tenant_c','tenant_d')", Long.class)));
    }

    private void reserve(String id, String tenant, LocalDateTime createdAt, long tokens, boolean counter) {
        jdbc.update("""
                INSERT INTO t_kb_model_usage (usage_id,tenant_id,source,provider,capability,model,status,reserved_tokens,created_at)
                VALUES (?,?,'CONSOLE','fixture','CHAT','fixture-model','RESERVED',?,?)
                """, id, tenant, tokens, createdAt);
        if (counter) jdbc.update("INSERT INTO t_kb_model_usage_monthly (tenant_id,usage_month,used_tokens,reserved_tokens) VALUES (?,?,0,?)", tenant, month, tokens);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig { }
}
