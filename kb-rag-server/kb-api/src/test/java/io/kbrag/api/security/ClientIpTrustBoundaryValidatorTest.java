package io.kbrag.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化容器不得先于应用层可信代理白名单处理转发头的启动契约。
 *
 * @author owlzhangfq@gmail.com
 */
class ClientIpTrustBoundaryValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TrustBoundaryConfiguration.class);

    @Test
    void shouldAcceptExplicitNoneWithoutTomcatForwardHeaders() {
        ServerProperties properties = safeServerProperties();

        assertDoesNotThrow(() -> validator(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectMissingForwardHeaderStrategy() {
        ServerProperties properties = safeServerProperties();
        properties.setForwardHeadersStrategy(null);

        assertInvalid(properties);
    }

    @Test
    void shouldRejectNativeForwardHeaderStrategy() {
        ServerProperties properties = safeServerProperties();
        properties.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NATIVE);

        assertInvalid(properties);
    }

    @Test
    void shouldRejectFrameworkForwardHeaderStrategy() {
        ServerProperties properties = safeServerProperties();
        properties.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.FRAMEWORK);

        assertInvalid(properties);
    }

    @Test
    void shouldRejectTomcatRemoteIpHeader() {
        ServerProperties properties = safeServerProperties();
        properties.getTomcat().getRemoteip().setRemoteIpHeader("x-forwarded-for");

        assertInvalid(properties);
    }

    @Test
    void shouldRejectTomcatProtocolHeader() {
        ServerProperties properties = safeServerProperties();
        properties.getTomcat().getRemoteip().setProtocolHeader("x-forwarded-proto");

        assertInvalid(properties);
    }

    @Test
    void shouldFailTheApplicationContextWhenEnvironmentOverridesStrategy() {
        contextRunner
                .withPropertyValues("server.forward-headers-strategy=native")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(ClientIpTrustBoundaryValidator.INVALID_CONFIGURATION_MESSAGE);
                });
    }

    private ServerProperties safeServerProperties() {
        ServerProperties properties = new ServerProperties();
        properties.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
        return properties;
    }

    private ClientIpTrustBoundaryValidator validator(ServerProperties properties) {
        return new ClientIpTrustBoundaryValidator(properties);
    }

    private void assertInvalid(ServerProperties properties) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator(properties).afterPropertiesSet());
        assertThat(error).hasMessage(ClientIpTrustBoundaryValidator.INVALID_CONFIGURATION_MESSAGE);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ServerProperties.class)
    @Import(ClientIpTrustBoundaryValidator.class)
    static class TrustBoundaryConfiguration {
    }
}
