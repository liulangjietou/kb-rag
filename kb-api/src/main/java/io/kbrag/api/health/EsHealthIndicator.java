package io.kbrag.api.health;

import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.FulltextStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch probe of {@code /actuator/health}.
 *
 * <p>Elasticsearch is mandatory in both deployment modes because it always serves the BM25 route.
 *
 * @author owlzhangfq@gmail.com
 */
@Component("esHealthIndicator")
@RequiredArgsConstructor
public class EsHealthIndicator implements HealthIndicator {

    private static final String DETAIL_KEY = "detail";

    private final FulltextStore fulltextStore;

    @Override
    public Health health() {
        HealthStatus status = fulltextStore.healthCheck();
        return status.isUp()
                ? Health.up().withDetail(DETAIL_KEY, status.getDetail()).build()
                : Health.down().withDetail(DETAIL_KEY, status.getDetail()).build();
    }
}
