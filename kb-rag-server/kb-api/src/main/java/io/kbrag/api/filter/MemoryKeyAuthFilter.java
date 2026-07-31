package io.kbrag.api.filter;

import io.kbrag.app.memory.MemoryAppKeyService;
import io.kbrag.app.memory.MemoryKeyPrincipal;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.openapi.ApiRateLimiter;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Authentication and rate limiting of the memory open API, the M19 contract.
 *
 * <p><b>A third chain, for the same reason there is a second.</b> A memory key is neither a console
 * session nor a knowledge API key: it carries a library binding, it has its own prefix, and its
 * failure surface must stay independent - which is exactly the reasoning that split
 * {@link ApiKeyAuthFilter} off the console interceptor. The rate limiter is shared: buckets are
 * keyed by key id, and the id prefixes of the two key families cannot collide.
 *
 * <p>Like its sibling, it runs as a servlet filter outside the reach of the
 * {@code @RestControllerAdvice}, so it writes the same error envelope itself.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MemoryKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated caller. */
    public static final String ATTR_PRINCIPAL = "kb.memoryKeyPrincipal";

    /** Path prefix of the whole memory open API surface; the admin surface uses another prefix. */
    public static final String MEMORY_API_PREFIX = "/api/v1/memory/";

    private final MemoryAppKeyService memoryAppKeyService;
    private final ApiRateLimiter apiRateLimiter;
    private final KbMetrics kbMetrics;
    private final KbProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(MEMORY_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MemoryKeyPrincipal principal;
        try {
            principal = authenticate(request);
        } catch (BizException e) {
            writeError(response, e);
            return;
        }
        try {
            apiRateLimiter.acquire(principal.getKeyId(), principal.getQpsLimit(),
                    properties.getOpenApi().getRetryAfterSeconds());
        } catch (BizException e) {
            writeError(response, e);
            return;
        }
        request.setAttribute(ATTR_PRINCIPAL, principal);
        filterChain.doFilter(request, response);
    }

    /**
     * Reads and verifies the {@code Authorization} header.
     *
     * @param request inbound request
     * @return authenticated caller
     */
    private MemoryKeyPrincipal authenticate(HttpServletRequest request) {
        String header = request.getHeader(KbConstants.AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(KbConstants.BEARER_PREFIX)) {
            throw new BizException(ErrorCode.INVALID_API_KEY,
                    "缺少 Authorization: Bearer " + KbConstants.MEMORY_KEY_PLAINTEXT_PREFIX + "* 请求头");
        }
        return memoryAppKeyService.authenticate(
                header.substring(KbConstants.BEARER_PREFIX.length()).trim());
    }

    /**
     * Writes the unified error envelope of a rejected call, counting it with the same open API
     * rejection metric as the knowledge surface - the error code keeps the two apart.
     *
     * @param response outbound response
     * @param e        rejection
     */
    private void writeError(HttpServletResponse response, BizException e) throws IOException {
        ErrorCode errorCode = e.getErrorCode();
        kbMetrics.recordOpenApiRejected(errorCode.name());
        log.info("memory api call rejected, errorCode={}, reason={}", errorCode, e.getMessage());
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (errorCode == ErrorCode.RATE_LIMITED) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    String.valueOf(properties.getOpenApi().getRetryAfterSeconds()));
        }
        response.getWriter().write(JsonUtil.toJson(Result.failure(errorCode, e.getMessage())));
    }
}
