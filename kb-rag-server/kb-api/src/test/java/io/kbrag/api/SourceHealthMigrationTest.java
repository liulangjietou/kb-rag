package io.kbrag.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 MySQL 验证 V33 至 V34：保留旧记录，旧写入兼容，不推定历史成功。 */
@EnabledIfEnvironmentVariable(named = "KB_SOURCE_MIGRATION_URL", matches = ".+")
class SourceHealthMigrationTest {
    @Test
    void shouldPreserveOldSourceRowsAndKeepNewHistoryUnknown() {
        String url = System.getenv("KB_SOURCE_MIGRATION_URL");
        if (!url.matches("jdbc:mysql://[^/]+/codex_source_health_migration_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Source migration requires a dedicated codex_source_health_migration_ schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("KB_SOURCE_TEST_USER"),
                System.getenv("KB_SOURCE_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("33").load().migrate();
        jdbc.update("""
                INSERT INTO t_kb_web_source (source_id,kb_id,url,url_hash,last_fetch_at,last_fetch_status)
                VALUES ('web_safe','kb_safe','https://example.test/docs',REPEAT('a',64),'2026-09-01 12:00:00','SUCCESS')
                """);
        jdbc.update("""
                INSERT INTO t_kb_ext_source (source_id,kb_id,source_type,name,endpoint,bucket,access_key,secret_key,last_sync_at,last_sync_status)
                VALUES ('ext_safe','kb_safe','s3','合成来源','https://example.test','bucket','synthetic','synthetic','2026-09-01 12:00:00','PARTIAL')
                """);
        var webBefore = jdbc.queryForMap("SELECT * FROM t_kb_web_source");
        var extBefore = jdbc.queryForMap("SELECT * FROM t_kb_ext_source");
        var upgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("34").load();
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        upgrade.validate();
        for (String table : List.of("t_kb_web_source", "t_kb_ext_source")) {
            var current = new HashMap<>(jdbc.queryForMap("SELECT * FROM " + table));
            assertTrue(current.containsKey("last_success_at"));
            assertNull(current.remove("last_success_at"));
            assertTrue(current.containsKey("last_content_change_at"));
            assertNull(current.remove("last_content_change_at"));
            assertEquals(table.equals("t_kb_web_source") ? webBefore : extBefore, current);
            // 旧二进制只更新原字段，不需要提供新增列。
            jdbc.update("UPDATE " + table + " SET sync_enabled=0");
            assertEquals(0, jdbc.queryForObject("SELECT sync_enabled FROM " + table, Integer.class));
        }
        assertEquals(0, upgrade.migrate().migrationsExecuted);
    }
}
