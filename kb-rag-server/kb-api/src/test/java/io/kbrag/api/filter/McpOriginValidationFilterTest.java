package io.kbrag.api.filter;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Origin 防 DNS rebinding 过滤器测试。
 *
 * @author owlzhangfq@gmail.com
 */
class McpOriginValidationFilterTest {

    private final McpOriginValidationFilter filter = new McpOriginValidationFilter(
            new String[]{"http://localhost:20002", "https://console.example.com"});

    @Test
    void browserOriginMustBelongToTheAllowList() throws Exception {
        MockHttpServletRequest request = request("/api/v1/knowledge/mcp");
        request.addHeader("Origin", "https://attacker.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowedBrowserOriginPassesToAuthenticationChain() throws Exception {
        MockHttpServletRequest request = request("/api/v1/memory/mcp");
        request.addHeader("Origin", "https://console.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void nonBrowserClientWithoutOriginPasses() throws Exception {
        MockHttpServletRequest request = request("/api/v1/knowledge/mcp");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void nonMcpEndpointIsOutsideTheFilterScope() throws Exception {
        MockHttpServletRequest request = request("/api/v1/knowledge/search");
        request.addHeader("Origin", "https://attacker.example.com");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
