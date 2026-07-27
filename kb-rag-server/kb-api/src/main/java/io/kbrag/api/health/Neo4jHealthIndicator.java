package io.kbrag.api.health;

import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.GraphStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Neo4j probe of {@code /actuator/health}.
 *
 * <p>Registered only when a Bolt URI is configured, the same rule Milvus follows: a deployment that runs
 * no graph must not report DOWN for a dependency it deliberately does not have.
 *
 * @author owlzhangfq@gmail.com
 */
@Component("neo4jHealthIndicator")
@RequiredArgsConstructor
@ConditionalOnExpression("!'${kb.graph.uri:}'.isBlank()")
public class Neo4jHealthIndicator implements HealthIndicator {

    private static final String DETAIL_KEY = "detail";

    private final GraphStore graphStore;

    @Override
    public Health health() {
        HealthStatus status = graphStore.healthCheck();
        return status.isUp()
                ? Health.up().withDetail(DETAIL_KEY, status.getDetail()).build()
                : Health.down().withDetail(DETAIL_KEY, status.getDetail()).build();
    }
}
