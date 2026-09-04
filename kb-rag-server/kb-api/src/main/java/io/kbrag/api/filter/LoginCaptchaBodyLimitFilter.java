package io.kbrag.api.filter;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 在 Jackson 分配对象前限制公开认证与注册请求的实际字节数。
 *
 * <p>同时检查声明长度并最多读取上限加一个字节，因此 Content-Length 缺失、造假、chunked
 * 或 HTTP/2 流都不能绕过。通过的短请求才会以缓存输入流交给 DispatcherServlet。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LoginCaptchaBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_VERIFY_BODY_BYTES = 32 * 1_024;
    static final int MAX_LOGIN_BODY_BYTES = 8 * 1_024;
    static final int MAX_REGISTRATION_CODE_BODY_BYTES = 4 * 1_024;
    static final int MAX_REGISTRATION_VERIFY_BODY_BYTES = 4 * 1_024;
    static final int MAX_REGISTRATION_BODY_BYTES = 16 * 1_024;

    private static final String VERIFY_PATH = "/api/v1/auth/captcha/verify";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTRATION_CODE_PATH = "/api/v1/registrations/verification-code";
    private static final String REGISTRATION_VERIFY_PATH = "/api/v1/registrations/verify-email";
    private static final String REGISTRATION_PATH = "/api/v1/registrations";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || bodyLimit(request) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        int bodyLimit = bodyLimit(request);
        if (request.getContentLengthLong() > bodyLimit) {
            writeTooLarge(response, bodyLimit);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(bodyLimit + 1);
        if (body.length > bodyLimit) {
            writeTooLarge(response, bodyLimit);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private int bodyLimit(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        if (VERIFY_PATH.equals(path)) {
            return MAX_VERIFY_BODY_BYTES;
        }
        if (LOGIN_PATH.equals(path)) {
            return MAX_LOGIN_BODY_BYTES;
        }
        if (REGISTRATION_CODE_PATH.equals(path)) {
            return MAX_REGISTRATION_CODE_BODY_BYTES;
        }
        if (REGISTRATION_VERIFY_PATH.equals(path)) {
            return MAX_REGISTRATION_VERIFY_BODY_BYTES;
        }
        if (REGISTRATION_PATH.equals(path)) {
            return MAX_REGISTRATION_BODY_BYTES;
        }
        return -1;
    }

    private String pathWithinApplication(HttpServletRequest request) {
        // 与 Spring MVC 保持同一套路径语义：同时移除矩阵参数、解码合法的百分号编码并剥离
        // context path，避免 /verify;x=1 或 /%76erify 仍命中 Controller 却绕过请求体上限。
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private void writeTooLarge(HttpServletResponse response, int bodyLimit) throws IOException {
        log.info("public request rejected, errorCode={}, bodyLimitBytes={}",
                ErrorCode.PAYLOAD_TOO_LARGE, bodyLimit);
        response.setStatus(ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtil.toJson(
                Result.failure(ErrorCode.PAYLOAD_TOO_LARGE, "public request body is too large")));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 请求体已经完整缓存，DispatcherServlet 只使用同步读取。
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
