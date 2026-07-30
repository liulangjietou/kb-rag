package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the SSRF gate of the M12 contract: URL import is the first outbound request built from
 * user input, so everything that could reach loopback, RFC1918, link local (the cloud metadata
 * endpoint lives there) or a smuggled credential has to be refused before a socket opens.
 *
 * <p>Every internal case uses an address literal, so no test depends on a resolver being online.
 *
 * @author owlzhangfq@gmail.com
 */
class UrlGuardTest {

    private final KbProperties properties = new KbProperties();
    private final UrlGuard guard = new UrlGuard(properties);

    @Test
    void shouldAcceptAPublicHttpUrl() {
        // A public address literal: accepted without any DNS round trip.
        URI uri = guard.validate("http://93.184.216.34/docs/guide");

        assertEquals("93.184.216.34", uri.getHost());
    }

    @Test
    void shouldRejectABlankUrl() {
        assertThrows(BizException.class, () -> guard.validate("   "));
        assertThrows(BizException.class, () -> guard.validate(null));
    }

    @Test
    void shouldRejectAMalformedUrl() {
        assertThrows(BizException.class, () -> guard.validate("http://exa mple.com/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://93.184.216.34/file", "file:///etc/passwd", "gopher://93.184.216.34/"})
    void shouldRejectAnyNonHttpScheme(String url) {
        assertThrows(BizException.class, () -> guard.validate(url));
    }

    @Test
    void shouldRejectCredentialsInsideTheUrl() {
        // "http://internal@evil/" style smuggling: the userinfo part is refused outright.
        assertThrows(BizException.class, () -> guard.validate("http://admin:secret@93.184.216.34/"));
    }

    @Test
    void shouldRejectAUrlWithoutAHost() {
        assertThrows(BizException.class, () -> guard.validate("http:///path-only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Loopback, IPv4 and IPv6.
            "http://127.0.0.1/", "http://localhost/", "http://[::1]/",
            // RFC1918 private ranges.
            "http://10.0.0.1/", "http://172.16.0.1/", "http://192.168.1.1/",
            // Link local, which is where 169.254.169.254 - the metadata endpoint - lives.
            "http://169.254.169.254/latest/meta-data/",
            // Wildcard.
            "http://0.0.0.0/"})
    void shouldRejectEveryInternalAddress(String url) {
        assertThrows(BizException.class, () -> guard.validate(url));
    }

    @Test
    void shouldAdmitAnInternalAddressWhenTheSwitchDisarmsTheGuard() {
        // The development escape hatch: with the switch on, loopback is admitted - while a smuggled
        // credential is still refused, because the switch disarms only the address rejection.
        properties.getWebImport().setAllowInternalAddress(true);

        assertEquals("127.0.0.1", guard.validate("http://127.0.0.1/page").getHost());
        assertThrows(BizException.class, () -> guard.validate("http://admin:secret@127.0.0.1/"));
    }
}
