package io.kbrag.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the periodic tasks of the service.
 *
 * <p>Kept as its own configuration rather than an annotation on the application class so a test can
 * exclude it and the scheduler never starts inside a unit test context.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
