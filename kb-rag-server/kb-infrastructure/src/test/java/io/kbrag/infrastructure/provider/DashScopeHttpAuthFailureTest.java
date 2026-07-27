package io.kbrag.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the credential-failure classification of the provider transport.
 *
 * <p>A rejected API key must be reported as {@link ProviderErrorType#AUTH_FAILED}. This used to
 * come back as {@link ProviderErrorType#NETWORK_UNREACHABLE}: the transport ran on
 * HttpURLConnection, which cannot replay a streamed request body, so a 401 answered with a
 * {@code WWW-Authenticate} header was raised as an I/O error and the status code never reached the
 * classifier. Operators then chased connectivity while the real cause was the key. The test drives
 * a real local HTTP server because the bug lived in the HTTP client, not in the classifier, and a
 * mocked exchange would not reproduce it.
 *
 * @author owlzhangfq@gmail.com
 */
class DashScopeHttpAuthFailureTest {

    private static final String AUTH_ERROR_BODY =
            "{\"error\":{\"message\":\"Incorrect API key provided.\",\"code\":\"invalid_api_key\"}}";
    private static final int TIMEOUT_MS = 5_000;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // The WWW-Authenticate header is what pushed HttpURLConnection into its retry path.
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"dashscope\"");
            byte[] body = AUTH_ERROR_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectedKeyIsClassifiedAsAuthFailure() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient client = DashScopeHttp.client(baseUrl, "wrong-key", TIMEOUT_MS);

        assertThatThrownBy(() -> DashScopeHttp.post(client, "/embeddings",
                Map.of("model", "text-embedding-v4", "input", "hello"), "dashscope", "embedding"))
                .isInstanceOf(ProviderException.class)
                .satisfies(thrown -> {
                    ProviderException failure = (ProviderException) thrown;
                    assertThat(failure.getErrorType()).isEqualTo(ProviderErrorType.AUTH_FAILED);
                    assertThat(failure.getMessage()).contains("401");
                });
    }
}
