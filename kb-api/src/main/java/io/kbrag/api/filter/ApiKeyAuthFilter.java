package io.kbrag.api.filter;

import io.kbrag.app.openapi.ApiAuditService;
import io.kbrag.app.openapi.ApiKeyPrincipal;
import io.kbrag.app.openapi.ApiKeyService;
import io.kbrag.app.openapi.ApiRateLimiter;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.context.RequestIdHolder;
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
 * Authentication and rate limiting of the open API, a chain of its own, requirement section 4.8.
 *
 * <p><b>Why a separate chain and not the console's interceptor.</b> The two surfaces have different credentials
 * (an API key versus a console session token), different failure codes, different rate limiting and different
 * audit trails. Sharing one entry point would mean one class deciding which of the two identities applies,
 * which is exactly the kind of branch an authentication bug hides in.
 *
 * <p>It runs as a servlet filter because rejecting a call before the dispatcher touches it keeps an
 * unauthenticated request away from every controller. That places it outside the reach of the
 * {@code @RestControllerAdvice}, so it writes the same error envelope itself rather than relying on a handler
 * that will not be invoked.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated caller. */
    public static final String ATTR_PRINCIPAL = "kb.apiKeyPrincipal";

    /** Path prefix of the whole open API surface. */
    public static final String OPEN_API_PREFIX = "/api/v1/knowledge/";

    private final ApiKeyService apiKeyService;
    private final ApiRateLimiter apiRateLimiter;
    private final ApiAuditService apiAuditService;
    private final KbProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(OPEN_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ApiKeyPrincipal principal;
        try {
            principal = authenticate(request);
        } catch (BizException e) {
            // No audit row: an unknown or malformed credential has no key id the audit trail could reference,
            // and inventing one would make the table unqueryable by the dimension it is indexed on.
            writeError(response, e);
            return;
        }
        try {
            apiRateLimiter.acquire(principal.getKeyId(), principal.getQpsLimit(),
                    properties.getOpenApi().getRetryAfterSeconds());
        } catch (BizException e) {
            // Audited, unlike the two authentication failures: the key is known here, and a caller running into
            // its quota is exactly the pattern an operator looks for on the audit page.
            auditRejection(request, principal, e);
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
    private ApiKeyPrincipal authenticate(HttpServletRequest request) {
        String header = request.getHeader(KbConstants.AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(KbConstants.BEARER_PREFIX)) {
            throw new BizException(ErrorCode.INVALID_API_KEY,
                    "缺少 Authorization: Bearer " + KbConstants.API_KEY_PLAINTEXT_PREFIX + "* 请求头");
        }
        return apiKeyService.authenticate(header.substring(KbConstants.BEARER_PREFIX.length()).trim());
    }

    /**
     * Records a call the filter itself rejected.
     *
     * <p>The query is not read: the body would have to be consumed to see it, and a rejected call must not have
     * its payload buffered just to be described. The endpoint, the key and the error code are what the audit
     * page filters on anyway.
     *
     * @param request   inbound request
     * @param principal authenticated caller
     * @param e         rejection
     */
    private void auditRejection(HttpServletRequest request, ApiKeyPrincipal principal, BizException e) {
        String uri = request.getRequestURI();
        String endpoint = uri.substring(uri.lastIndexOf('/') + 1);
        apiAuditService.recordAsync(ApiAuditService.AuditRecord.builder()
                .keyId(principal.getKeyId())
                .endpoint(endpoint)
                .latencyMs(0)
                .errorCode(e.getErrorCode().name())
                .requestId(RequestIdHolder.get())
                .build());
    }

    /**
     * Writes the unified error envelope of a rejected call.
     *
     * <p>A rate limited response also carries {@code Retry-After}, which is what lets a well behaved agent back
     * off by the amount the server actually wants rather than by a guess.
     *
     * @param response outbound response
     * @param e        rejection
     */
    private void writeError(HttpServletResponse response, BizException e) throws IOException {
        ErrorCode errorCode = e.getErrorCode();
        log.info("open api call rejected, errorCode={}, reason={}", errorCode, e.getMessage());
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
