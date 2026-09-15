package io.kbrag.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 独立 MySQL 升级验证：访问记录增量建表，不改变既有业务记录与旧写入。 */
@EnabledIfEnvironmentVariable(named = "KB_VISIT_MIGRATION_URL", matches = ".+")
class ResourceVisitMigrationTest {
    @Test
    void shouldAddPrivateVisitsWithoutChangingExistingBusinessData() {
        String url = System.getenv("KB_VISIT_MIGRATION_URL");
        if (!url.matches("jdbc:mysql://[^/]+/codex_resource_visit_migration_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Visit migration requires a dedicated codex_resource_visit_migration_ schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("KB_VISIT_TEST_USER"),
                System.getenv("KB_VISIT_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("32").load().migrate();
        jdbc.update("INSERT INTO t_kb_quality_issue (issue_id,kb_id,source_type,source_id,summary,status) VALUES (?,?,?,?,?,?)",
                "issue_safe", "kb_safe", "ZERO_HIT", "source_safe", "合成问题", "OPEN");
        var before = jdbc.queryForMap("SELECT * FROM t_kb_quality_issue");
        var upgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("33").load();
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        upgrade.validate();
        assertEquals(before, jdbc.queryForMap("SELECT * FROM t_kb_quality_issue"));
        String columns = "SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_kb_resource_visit' AND index_name=? ORDER BY seq_in_index";
        assertEquals(List.of("tenant_id", "user_id", "resource_type", "resource_id"),
                jdbc.queryForList(columns, String.class, "uk_resource_visit_owner"));
        assertEquals(List.of("tenant_id", "user_id", "visited_at", "id"),
                jdbc.queryForList(columns, String.class, "idx_resource_visit_recent"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_resource_visit", Integer.class));
        assertEquals(0, upgrade.migrate().migrationsExecuted);
        // 新表不会要求旧版本业务写入方补充字段；回滚旧二进制时可以保留这张空闲表。
        jdbc.update("UPDATE t_kb_quality_issue SET summary=? WHERE issue_id=?", "旧写入仍可执行", "issue_safe");
        assertEquals("旧写入仍可执行", jdbc.queryForObject("SELECT summary FROM t_kb_quality_issue", String.class));
    }
}
