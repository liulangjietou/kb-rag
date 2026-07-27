package io.kbrag.api.health;

import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Qdrant probe of {@code /actuator/health}.
 *
 * <p>Registered only when a Qdrant URI is configured: the lite deployment does not run Qdrant, and
 * probing an engine that is not part of the deployment would make a healthy stack report as down.
 *
 * @author owlzhangfq@gmail.com
 */
@Component("qdrantHealthIndicator")
@RequiredArgsConstructor
@ConditionalOnExpression("!'${kb.qdrant.uri:}'.isBlank()")
public class QdrantHealthIndicator implements HealthIndicator {

    private static final String DETAIL_KEY = "detail";
    private static final String DETAIL_NOT_ACTIVE = "qdrant configured but not the active vector engine";

    private final VectorStore vectorStore;

    @Override
    public Health health() {
        if (!VectorEngine.QDRANT.code().equals(vectorStore.engine())) {
            return Health.up().withDetail(DETAIL_KEY, DETAIL_NOT_ACTIVE).build();
        }
        HealthStatus status = vectorStore.healthCheck();
        return status.isUp()
                ? Health.up().withDetail(DETAIL_KEY, status.getDetail()).build()
                : Health.down().withDetail(DETAIL_KEY, status.getDetail()).build();
    }
}
