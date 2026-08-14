package io.kbrag.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the secure-by-default management listener contract without starting external dependencies.
 *
 * @author owlzhangfq@gmail.com
 */
class ActuatorConfigurationTest {

    private static final String APPLICATION_CONFIG = "application.yml";

    @Test
    void shouldKeepActuatorOnAnIndependentLoopbackListener() throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of());

        assertEquals("20003", environment.getProperty("management.server.port"));
        assertEquals("127.0.0.1", environment.getProperty("management.server.address"));
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
        assertEquals("health,info,prometheus",
                environment.getProperty("management.endpoints.web.exposure.include"));
        assertNotEquals(environment.getProperty("server.port"),
                environment.getProperty("management.server.port"));
    }

    @Test
    void shouldAllowDeploymentToMoveTheIsolatedManagementListener() throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of(
                "MANAGEMENT_SERVER_PORT", "19090",
                "MANAGEMENT_SERVER_ADDRESS", "10.0.0.8"));

        assertEquals("19090", environment.getProperty("management.server.port"));
        assertEquals("10.0.0.8", environment.getProperty("management.server.address"));
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
    }

    private StandardEnvironment loadEnvironment(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource("testOverrides", overrides));
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load(APPLICATION_CONFIG, new ClassPathResource(APPLICATION_CONFIG));
        yamlSources.forEach(sources::addLast);
        return environment;
    }
}
