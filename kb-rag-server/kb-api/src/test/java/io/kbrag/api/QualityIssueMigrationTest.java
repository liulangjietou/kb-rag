package io.kbrag.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在独立 MySQL 临时库执行完整迁移链，不对业务库运行或清理。 */
@EnabledIfEnvironmentVariable(named = "KB_QUALITY_MIGRATION_URL", matches = ".+")
class QualityIssueMigrationTest {
    @Test
    void shouldUpgradeFromThirtyWithoutChangingOldEvaluationInputs() {
        String url = System.getenv("KB_QUALITY_MIGRATION_URL");
        if (!url.matches("jdbc:mysql://[^/]+/codex_quality_migration_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("Migration tests require a dedicated codex_quality_migration_ schema");
        }
        var source = new DriverManagerDataSource(url, System.getenv("KB_QUALITY_TEST_USER"),
                System.getenv("KB_QUALITY_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("30").load().migrate();
        jdbc.update("INSERT INTO t_kb_eval_run (run_id,dataset_id,kb_id,dataset_revision,corpus_fingerprint,retrieval_config,status,case_inputs) VALUES (?,?,?,?,?,?,?,?)",
                "run_old", "dataset_old", "kb_old", 3, "corpus-old", "{}", "SUCCESS", "[]");
        var old = jdbc.queryForMap("SELECT run_id,dataset_revision,corpus_fingerprint,status,case_inputs FROM t_kb_eval_run WHERE run_id='run_old'");
        var current = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        assertEquals(1, current.migrate().migrationsExecuted);
        current.validate();
        assertEquals(old, jdbc.queryForMap("SELECT run_id,dataset_revision,corpus_fingerprint,status,case_inputs FROM t_kb_eval_run WHERE run_id='run_old'"));
        assertEquals(0, current.migrate().migrationsExecuted);
        jdbc.update("UPDATE t_kb_eval_run SET case_total=1 WHERE run_id='run_old'");
        assertEquals("[]", jdbc.queryForObject("SELECT case_inputs FROM t_kb_eval_run WHERE run_id='run_old'", String.class));
        jdbc.update("INSERT INTO t_kb_eval_run (run_id,dataset_id,kb_id,dataset_revision,corpus_fingerprint,retrieval_config) VALUES (?,?,?,?,?,?)",
                "legacy_writer", "dataset_old", "kb_old", 3, "corpus-old", "{}");
        assertNull(jdbc.queryForObject("SELECT case_inputs FROM t_kb_eval_run WHERE run_id='legacy_writer'", String.class));
        String insert = "INSERT INTO t_kb_quality_issue (issue_id,kb_id,source_type,source_id,summary) VALUES (?,?,?,?,?)";
        jdbc.update(insert, "issue", "kb_old", "ZERO_HIT", "source", "合成摘要");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(insert, "other", "kb_old", "ZERO_HIT", "source", "重复来源"));
        jdbc.update(insert, "separate", "kb_other", "ZERO_HIT", "source", "其他知识库");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_kb_quality_issue_record", Integer.class));
    }
}
