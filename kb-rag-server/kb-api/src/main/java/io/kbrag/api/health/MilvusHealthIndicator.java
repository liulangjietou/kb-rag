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
 * Milvus probe of {@code /actuator/health}.
 *
 * <p>Registered only when a Milvus URI is configured: the lite deployment does not run Milvus, and
 * probing an engine that is not part of the deployment would make a healthy stack report as down.
 *
 * @author owlzhangfq@gmail.com
 */
@Component("milvusHealthIndicator")
@RequiredArgsConstructor
@ConditionalOnExpression("!'${kb.milvus.uri:}'.isBlank()")
public class MilvusHealthIndicator implements HealthIndicator {

    private static final String DETAIL_KEY = "detail";
    private static final String DETAIL_NOT_ACTIVE = "milvus configured but not the active vector engine";

    private final VectorStore vectorStore;

    @Override
    public Health health() {
        if (!VectorEngine.MILVUS.code().equals(vectorStore.engine())) {
            return Health.up().withDetail(DETAIL_KEY, DETAIL_NOT_ACTIVE).build();
        }
        HealthStatus status = vectorStore.healthCheck();
        return status.isUp()
                ? Health.up().withDetail(DETAIL_KEY, status.getDetail()).build()
                : Health.down().withDetail(DETAIL_KEY, status.getDetail()).build();
    }
}
