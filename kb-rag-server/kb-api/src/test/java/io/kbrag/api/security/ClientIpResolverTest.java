package io.kbrag.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 固化可信代理解析边界，防止 X-Forwarded-For 被直连调用方伪造。
 *
 * @author owlzhangfq@gmail.com
 */
class ClientIpResolverTest {

    @Test
    void shouldIgnoreForwardedHeadersFromAnUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");
        HttpServletRequest request = request("198.51.100.7", "203.0.113.99");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void shouldTakeTheRightmostUntrustedAddressAndIgnoreSpoofedPrefixes() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32,10.0.0.0/8");

        assertEquals("198.51.100.8", resolver.resolve(request(
                "127.0.0.1", "203.0.113.250, 198.51.100.8")));
        assertEquals("198.51.100.9", resolver.resolve(request(
                "127.0.0.1", "203.0.113.250, 198.51.100.9, 10.2.3.4")));
    }

    @Test
    void shouldFailClosedForMalformedOrOversizedForwardedChains() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");
        String tooManyHops = String.join(",", java.util.Collections.nCopies(11, "198.51.100.1"));

        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "not-an-ip")));
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", tooManyHops)));
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "x".repeat(1_025))));
    }

    @Test
    void shouldResolveIpv6AcrossTrustedProxyHops() {
        ClientIpResolver resolver = new ClientIpResolver("::1/128,2001:db8:1::/64");

        assertEquals("2001:db8:ffff:0:0:0:0:8", resolver.resolve(request(
                "::1", "2001:db8:ffff::8, 2001:db8:1::42")));
    }

    @Test
    void shouldRejectUnsafeOrMalformedTrustedProxyConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("::/0"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("proxy.example.com/32"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("127.0.0.1/33"));
    }

    private HttpServletRequest request(String remoteAddress, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
