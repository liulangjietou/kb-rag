package io.kbrag.api.config;

import io.kbrag.api.filter.AuthInterceptor;
import io.kbrag.api.filter.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC wiring of the management API: authentication interception and the CORS allow list.
 *
 * <p>The allow list is explicit and never a wildcard, and origin reflection is not used: the console is
 * the only intended caller of these endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String API_PATTERN = "/api/**";
    private static final String INTERNAL_PATTERN = "/internal/**";
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/login",
            // Tells the login page whether to offer the single sign-on tab. It has to be answerable before
            // anybody is authenticated, and it discloses one boolean about the deployment's own wiring.
            "/api/v1/auth/sso-available",
            "/actuator/**",
            // The open API has its own authentication chain (ApiKeyAuthFilter): an API key is not a console
            // session, and letting the console interceptor see these paths would reject every external call
            // for missing a token it is not supposed to have.
            "/api/v1/knowledge/**",
            // The ik tokenizer polls its remote dictionary from inside Elasticsearch with a plain HTTP
            // client that cannot carry a bearer token, so this path is deliberately unauthenticated.
            // It only serves the domain terms an operator entered on purpose: no document content, no
            // configuration, no personal data. See InternalDictController for the full reasoning.
            "/internal/dict/ik/**",
            "/error");

    private static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    private final AuthInterceptor authInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    /**
     * Console origins allowed to call the management API.
     *
     * <p>The effective value comes from {@code kb.web.allowed-origins} in application.yml, which is
     * itself overridable through the {@code CORS_ALLOWED_ORIGINS} environment variable. The default
     * here only covers contexts that load this bean without the yml, such as slice tests.
     */
    @Value("${kb.web.allowed-origins:http://localhost:20002,http://127.0.0.1:20002}")
    private String[] allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The internal pattern is registered and then excluded rather than simply left out, so the
        // exemption is a visible decision in this list instead of an accident of the include pattern.
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(API_PATTERN, INTERNAL_PATTERN)
                .excludePathPatterns(PUBLIC_PATHS);
        // Order matters and is given by the registration order: the permission check reads the caller the
        // authentication interceptor binds, so it has to come second and cover the same paths.
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns(API_PATTERN, INTERNAL_PATTERN)
                .excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(API_PATTERN)
                .allowedOrigins(allowedOrigins)
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders("*")
                .maxAge(CORS_MAX_AGE_SECONDS);
    }
}
