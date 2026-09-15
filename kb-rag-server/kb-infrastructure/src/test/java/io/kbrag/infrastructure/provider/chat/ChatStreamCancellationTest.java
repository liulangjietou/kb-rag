package io.kbrag.infrastructure.provider.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实 HTTP 连接上的取消、超时与计费边界；不用模拟 Future 代替传输验证。 */
class ChatStreamCancellationTest {

    @Test
    void shouldNotReserveOrSendWhenAlreadyCancelled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.cancellation.cancel();
            assertThrows(CancellationException.class, () -> fixture.provider().stream(null,
                    List.of(ChatMessage.user("问题")), delta -> { }, fixture.cancellation));
            verifyNoInteractions(fixture.meter);
        }
    }

    @Test
    void shouldCancelWhileWaitingForResponseHeaders() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.createContext("/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                fixture.received.countDown();
                fixture.awaitRelease();
                exchange.close();
            });
            Future<?> call = fixture.start();
            assertTrue(fixture.received.await(3, TimeUnit.SECONDS));
            fixture.cancellation.cancel();
            assertInstanceOf(CancellationException.class, failureOf(call));
            verify(fixture.meter).incomplete(eq(fixture.ticket), eq(ModelTokenUsage.unknown()),
                    any(CancellationException.class));
            verify(fixture.meter, never()).fail(any(), any());
        }
    }

    @Test
    void shouldCloseTheUpstreamConnectionAfterFirstDelta() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch disconnected = new CountDownLatch(1);
            fixture.server.createContext("/chat/completions", exchange -> {
                fixture.writeFirst(exchange);
                fixture.awaitRelease();
                try {
                    // 继续写足够数据，证明对端资源已关闭，而不只是本地停止回调。
                    for (int index = 0; index < 256; index++) {
                        exchange.getResponseBody().write(new byte[16_384]);
                        exchange.getResponseBody().flush();
                    }
                } catch (IOException expected) {
                    disconnected.countDown();
                } finally {
                    exchange.close();
                }
            });
            Future<?> call = fixture.start();
            assertTrue(fixture.firstDelta.await(3, TimeUnit.SECONDS));
            fixture.cancellation.cancel();
            assertInstanceOf(CancellationException.class, failureOf(call));
            fixture.release.countDown();
            assertTrue(disconnected.await(3, TimeUnit.SECONDS), "上游应观察到真实连接关闭");
            verify(fixture.meter).incomplete(eq(fixture.ticket), eq(ModelTokenUsage.unknown()),
                    any(CancellationException.class));
            verify(fixture.meter, never()).succeed(any(), any());
        }
    }

    @Test
    void shouldEnforceDeadlineDuringASilentResponseBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.config.setGenerateTimeoutMs(500);
            fixture.server.createContext("/chat/completions", exchange -> {
                fixture.writeFirst(exchange);
                fixture.awaitRelease();
                exchange.close();
            });
            Future<?> call = fixture.start();
            assertTrue(fixture.firstDelta.await(3, TimeUnit.SECONDS));
            assertInstanceOf(ProviderException.class, failureOf(call));
            verify(fixture.meter).incomplete(eq(fixture.ticket), eq(ModelTokenUsage.unknown()),
                    any(ProviderException.class));
            verify(fixture.meter, never()).fail(any(), any());
        }
    }

    @Test
    void shouldReleaseOnlyForAnExplicitHttpRejection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.createContext("/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            ProviderException failure = assertInstanceOf(ProviderException.class, failureOf(fixture.start()));
            assertEquals(ProviderErrorType.AUTH_FAILED, failure.getErrorType());
            verify(fixture.meter).fail(eq(fixture.ticket), any(ProviderException.class));
            verify(fixture.meter, never()).incomplete(any(), any(), any());
        }
    }

    private Throwable failureOf(Future<?> call) {
        return assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSettleReportedUsageEvenWhenTheTerminalFrameIsMissing(boolean done) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.createContext("/chat/completions", exchange -> {
                fixture.writeFirst(exchange);
                exchange.getResponseBody().write(("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
                        + "\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                        + (done ? "data: [DONE]\n\n" : "")).getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
            Future<?> call = fixture.start();
            ModelTokenUsage known = new ModelTokenUsage(10L, 5L, 15L, true);
            if (done) {
                call.get(3, TimeUnit.SECONDS);
                verify(fixture.meter).succeed(fixture.ticket, known);
                verify(fixture.meter, never()).incomplete(any(), any(), any());
            } else {
                assertInstanceOf(ProviderException.class, failureOf(call));
                verify(fixture.meter).incomplete(eq(fixture.ticket), eq(known), any(ProviderException.class));
                verify(fixture.meter, never()).succeed(any(), any());
            }
            verify(fixture.meter, never()).fail(any(), any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch firstDelta = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ChatCancellation cancellation = new ChatCancellation();
        private final KbProperties.Chat config = new KbProperties.Chat();
        private final ModelCallMeter meter = mock(ModelCallMeter.class);
        private final ModelCallTicket ticket = new ModelCallTicket("fixture", 100L, true);

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setApiKey("fixture-key");
            when(meter.reserve(any())).thenReturn(ticket);
        }

        private DashScopeChatProvider provider() {
            return new DashScopeChatProvider(config, meter);
        }

        private Future<?> start() {
            server.start();
            return worker.submit(() -> provider().stream(null, List.of(ChatMessage.user("问题")),
                    delta -> firstDelta.countDown(), cancellation));
        }

        private void writeFirst(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }

        private void awaitRelease() {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws InterruptedException {
            cancellation.cancel();
            release.countDown();
            server.stop(0);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
