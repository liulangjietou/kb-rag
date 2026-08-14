package io.kbrag.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在身份认证之前校验两个 MCP 端点的 Origin，阻断浏览器 DNS rebinding 请求。
 *
 * <p>非浏览器 MCP 客户端通常不发送 Origin，此时直接放行；只要请求携带 Origin，就必须
 * 与管理台 CORS 白名单完全匹配。放在 API Key 过滤器之前，保证非法来源不会先消耗鉴权与
 * 限流资源，也不会因凭证状态不同泄露额外信息。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class McpOriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> MCP_ENDPOINTS = Set.of(
            "/api/v1/knowledge/mcp", "/api/v1/memory/mcp");

    private final Set<String> allowedOrigins;

    public McpOriginValidationFilter(
            @Value("${kb.web.allowed-origins:http://localhost:20002,http://127.0.0.1:20002}")
            String[] allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MCP_ENDPOINTS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            log.info("mcp origin rejected, origin={}, uri={}", origin, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
