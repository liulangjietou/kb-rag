package io.kbrag.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固化验证码请求体在反序列化前的长度门禁，包括未知长度的流式请求。
 *
 * @author owlzhangfq@gmail.com
 */
class LoginCaptchaBodyLimitFilterTest {

    private final LoginCaptchaBodyLimitFilter filter = new LoginCaptchaBodyLimitFilter();

    @Test
    void shouldPassABoundedBodyToTheDispatcherUnchanged() throws Exception {
        byte[] body = "{\"challenge_id\":\"id\",\"track\":[]}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = verifyRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertEquals(new String(body, StandardCharsets.UTF_8),
                new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldRejectADeclaredOversizedBodyBeforeDispatch() throws Exception {
        MockHttpServletRequest request = verifyRequest(
                new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        assertNull(chain.getRequest());
    }

    @Test
    void shouldAllowABodyAtTheExactVerifyLimit() throws Exception {
        MockHttpServletRequest request = verifyRequest(
                new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertEquals(LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES,
                forwarded.getInputStream().readAllBytes().length);
    }

    @Test
    void shouldRejectAnOversizedChunkedBodyByCountingActualBytes() throws Exception {
        MockHttpServletRequest source = verifyRequest(
                new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]);
        HttpServletRequest unknownLength = new HttpServletRequestWrapper(source) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(unknownLength, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("authentication request body is too large"));
        assertNull(chain.getRequest());
    }

    @Test
    void shouldRejectAnOversizedBodyWhenTheDeclaredLengthLies() throws Exception {
        MockHttpServletRequest source = verifyRequest(
                new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]);
        HttpServletRequest lyingLength = new HttpServletRequestWrapper(source) {
            @Override
            public int getContentLength() {
                return 1;
            }

            @Override
            public long getContentLengthLong() {
                return 1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(lyingLength, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldRejectAnOversizedLoginBeforeJacksonOrProofConsumption() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[LoginCaptchaBodyLimitFilter.MAX_LOGIN_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void shouldNotApplyTheCaptchaLimitToOtherRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        request.setContent(new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    void shouldRejectMatrixParameterVariantThatSpringMapsToVerifyController() throws Exception {
        AuthenticationController controller = new AuthenticationController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();

        mockMvc.perform(post("/api/v1/auth/captcha/verify;x=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());

        assertEquals(0, controller.invocations.get());
    }

    @Test
    void shouldRejectEncodedVerifyPathThatSpringMapsToVerifyController() throws Exception {
        AuthenticationController controller = new AuthenticationController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();

        mockMvc.perform(post(URI.create("/api/v1/auth/captcha/%76erify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[LoginCaptchaBodyLimitFilter.MAX_VERIFY_BODY_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());

        assertEquals(0, controller.invocations.get());
    }

    @Test
    void shouldRejectEncodedLoginPathThatSpringMapsToLoginController() throws Exception {
        AuthenticationController controller = new AuthenticationController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();

        mockMvc.perform(post(URI.create("/api/v1/auth/%6cogin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[LoginCaptchaBodyLimitFilter.MAX_LOGIN_BODY_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());

        assertEquals(0, controller.invocations.get());
    }

    private MockHttpServletRequest verifyRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/auth/captcha/verify");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    @RestController
    private static final class AuthenticationController {

        private final AtomicInteger invocations = new AtomicInteger();

        @PostMapping("/api/v1/auth/captcha/verify")
        void verify(@RequestBody byte[] body) {
            invocations.incrementAndGet();
        }

        @PostMapping("/api/v1/auth/login")
        void login(@RequestBody byte[] body) {
            invocations.incrementAndGet();
        }
    }
}
