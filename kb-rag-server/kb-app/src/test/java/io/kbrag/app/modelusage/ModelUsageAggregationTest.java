package io.kbrag.app.modelusage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.kbrag.domain.mapper.ModelUsageMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 执行真实 Mapper SQL，保证失败或取消后的有费调用仍进入成本与未知用量汇总。 */
class ModelUsageAggregationTest {

    @Test
    void shouldAggregateSettledSpendAcrossTerminalStatesAndRespectTenantMonthAndDeletion() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:usage_aggregation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS t_kb_model_usage");
        jdbc.execute("""
                CREATE TABLE t_kb_model_usage (
                    tenant_id VARCHAR(64), created_at TIMESTAMP, status VARCHAR(16),
                    priced INT, estimated INT, total_tokens BIGINT, currency VARCHAR(8),
                    cost_micros BIGINT, deleted INT)
                """);
        jdbc.execute("""
                INSERT INTO t_kb_model_usage VALUES
                  ('tenant-a','2026-09-10','SUCCEEDED',1,0,10,'CNY',100,0),
                  ('tenant-a','2026-09-10','CANCELLED',1,1,20,'CNY',200,0),
                  ('tenant-a','2026-09-10','FAILED',1,1,30,'USD',300,0),
                  ('tenant-a','2026-09-10','CANCELLED',0,1,40,NULL,0,0),
                  ('tenant-a','2026-09-10','FAILED',0,0,0,NULL,0,0),
                  ('tenant-a','2026-09-10','RESERVED',0,0,0,NULL,0,0),
                  ('tenant-b','2026-09-10','CANCELLED',1,1,20,'CNY',999,0),
                  ('tenant-a','2026-08-10','CANCELLED',1,1,20,'CNY',999,0),
                  ('tenant-a','2026-09-10','CANCELLED',1,1,20,'CNY',999,1)
                """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.addMapper(ModelUsageMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession()) {
            ModelUsageMapper mapper = session.getMapper(ModelUsageMapper.class);
            LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
            LocalDateTime end = start.plusMonths(1);
            var costs = mapper.sumCostByCurrency("tenant-a", start, end);
            assertEquals(2, costs.size());
            assertEquals("CNY", costs.get(0).currency());
            assertEquals(300L, costs.get(0).costMicros());
            assertEquals("USD", costs.get(1).currency());
            assertEquals(300L, costs.get(1).costMicros());
            assertEquals(3, mapper.countEstimated("tenant-a", start, end));
            assertEquals(1, mapper.countUnpriced("tenant-a", start, end));
        }
        jdbc.execute("DROP TABLE t_kb_model_usage");
    }
}
