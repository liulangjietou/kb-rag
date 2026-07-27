package io.kbrag.api.filter;

import io.kbrag.common.context.RequestIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds a correlation id to every request.
 *
 * <p>An inbound {@code X-Request-Id} is honoured so a caller can supply its own trace id; otherwise
 * one is generated. The value goes into the logging context, into every response envelope and into
 * the response header, and it is forwarded to the parser service and the model providers.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestIdHolder.set(request.getHeader(RequestIdHolder.HEADER_NAME));
        response.setHeader(RequestIdHolder.HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdHolder.clear();
        }
    }
}
