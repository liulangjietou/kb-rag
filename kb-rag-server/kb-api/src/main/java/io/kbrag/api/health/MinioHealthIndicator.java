package io.kbrag.api.health;

import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Object storage probe of {@code /actuator/health}.
 *
 * @author owlzhangfq@gmail.com
 */
@Component("minioHealthIndicator")
@RequiredArgsConstructor
public class MinioHealthIndicator implements HealthIndicator {

    private static final String DETAIL_KEY = "detail";

    private final ObjectStorage objectStorage;

    @Override
    public Health health() {
        HealthStatus status = objectStorage.healthCheck();
        return status.isUp()
                ? Health.up().withDetail(DETAIL_KEY, status.getDetail()).build()
                : Health.down().withDetail(DETAIL_KEY, status.getDetail()).build();
    }
}
