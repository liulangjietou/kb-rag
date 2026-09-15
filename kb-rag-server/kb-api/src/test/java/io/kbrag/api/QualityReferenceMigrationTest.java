package io.kbrag.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 在独立 MySQL 库核验引用索引升级，保证既有质量问题和回归输入不变。 */
@EnabledIfEnvironmentVariable(named = "KB_QUALITY_REFERENCE_MIGRATION_URL", matches = ".+")
class QualityReferenceMigrationTest {
    @Test
    void shouldIndexReferencesWithoutChangingExistingQualityHistory() {
        String url = System.getenv("KB_QUALITY_REFERENCE_MIGRATION_URL");
        if (!url.matches("jdbc:mysql://[^/]+/codex_quality_reference_migration_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Reference migration requires a dedicated codex_quality_reference_migration_ schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("KB_QUALITY_TEST_USER"),
                System.getenv("KB_QUALITY_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("31").load().migrate();
        jdbc.update("INSERT INTO t_kb_quality_issue (issue_id,kb_id,source_type,source_id,summary,status,dataset_id,case_id,expected_case_input) VALUES (?,?,?,?,?,?,?,?,?)",
                "issue_safe", "kb_safe", "ZERO_HIT", "source_safe", "合成问题", "RESOLVED", "dataset_safe", "case_safe", "{}");
        jdbc.update("INSERT INTO t_kb_quality_issue_record (issue_id,actor_user_id,actor_name,action,note) VALUES (?,?,?,?,?)",
                "issue_safe", "user_safe", "合成用户", "RESOLVED", "合成处理记录");
        var oldIssue = jdbc.queryForMap("SELECT * FROM t_kb_quality_issue");
        var oldRecord = jdbc.queryForMap("SELECT * FROM t_kb_quality_issue_record");
        var current = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        current.migrate();
        current.validate();
        String indexColumns = "SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_kb_quality_issue' AND index_name=? ORDER BY seq_in_index";
        assertEquals(List.of("dataset_id", "deleted"), jdbc.queryForList(indexColumns, String.class, "idx_quality_issue_dataset"));
        assertEquals(List.of("case_id", "deleted"), jdbc.queryForList(indexColumns, String.class, "idx_quality_issue_case"));
        assertEquals(oldIssue, jdbc.queryForMap("SELECT * FROM t_kb_quality_issue"));
        assertEquals(oldRecord, jdbc.queryForMap("SELECT * FROM t_kb_quality_issue_record"));
        assertEquals(0, current.migrate().migrationsExecuted);
        // 旧写入方不必感知新增索引，原有列和语句仍可工作。
        jdbc.update("UPDATE t_kb_quality_issue SET summary=? WHERE issue_id=?", "更新后的合成摘要", "issue_safe");
        assertEquals("{}", jdbc.queryForObject("SELECT expected_case_input FROM t_kb_quality_issue", String.class));
    }
}
