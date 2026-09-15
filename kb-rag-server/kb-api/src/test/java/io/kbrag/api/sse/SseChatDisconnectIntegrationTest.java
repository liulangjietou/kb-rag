package io.kbrag.api.sse;

import com.sun.net.httpserver.HttpServer;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.chat.DashScopeChatProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 嵌入式 Tomcat → 真实模型 HTTP 流：下游断开后，静默上游也会被心跳触发取消。 */
class SseChatDisconnectIntegrationTest {

    @Test
    void shouldCancelSilentGenerationWhenTheBrowserConnectionCloses() throws Exception {
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch generationCancelled = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseProvider.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        KbProperties.Chat config = new KbProperties.Chat();
        config.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort());
        config.setApiKey("fixture-key");
        ModelCallMeter meter = mock(ModelCallMeter.class);
        ModelCallTicket ticket = new ModelCallTicket("fixture", 100L, true);
        when(meter.reserve(any())).thenReturn(ticket);
        DashScopeChatProvider provider = new DashScopeChatProvider(config, meter);
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(WebConfiguration.class);
            context.registerBean("probeController", ProbeController.class,
                    () -> new ProbeController(context.getBean(SseChatStreamFactory.class), provider,
                            worker, generationCancelled));
            context.refresh();
            try (Socket browser = new Socket("127.0.0.1", context.getWebServer().getPort())) {
                browser.setSoTimeout(5_000);
                browser.getOutputStream().write(("GET /probe HTTP/1.1\r\nHost: localhost\r\n"
                        + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                browser.getOutputStream().flush();
                StringBuilder received = new StringBuilder();
                while (received.indexOf("ready") < 0 && received.length() < 16_384) {
                    int value = browser.getInputStream().read();
                    assertTrue(value >= 0, "收到部分答案之前连接不应关闭");
                    received.append((char) value);
                }
                assertTrue(received.indexOf("ready") >= 0);
                // 明确发送 TCP RST，使本用例验证服务器的断连处理而不是客户端连接池策略。
                browser.setSoLinger(true, 0);
            }
            assertTrue(generationCancelled.await(8, TimeUnit.SECONDS), "静默上游应在一次心跳周期后取消");
            verify(meter).incomplete(eq(ticket), eq(ModelTokenUsage.unknown()), any(CancellationException.class));
            verify(meter, never()).succeed(any(), any());
        } finally {
            releaseProvider.countDown();
            upstream.stop(0);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Configuration
    @EnableWebMvc
    static class WebConfiguration {
        @Bean
        TomcatServletWebServerFactory webServer() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setBaseDirectory(new java.io.File("target/chat-disconnect-tomcat"));
            return factory;
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcher(WebApplicationContext context) {
            return new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
        }

        @Bean
        SseChatStreamFactory streams() {
            return new SseChatStreamFactory();
        }
    }

    @RestController
    static class ProbeController {
        private final SseChatStreamFactory streams;
        private final DashScopeChatProvider provider;
        private final ExecutorService worker;
        private final CountDownLatch cancelled;

        ProbeController(SseChatStreamFactory streams, DashScopeChatProvider provider,
                        ExecutorService worker, CountDownLatch cancelled) {
            this.streams = streams;
            this.provider = provider;
            this.worker = worker;
            this.cancelled = cancelled;
        }

        @GetMapping(value = "/probe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            var listener = streams.create();
            worker.submit(() -> {
                try {
                    provider.stream(null, List.of(ChatMessage.user("问题")), listener::onDelta,
                            listener.cancellation());
                    listener.onDone("fixture", List.of(), List.of());
                } catch (CancellationException expected) {
                    cancelled.countDown();
                } finally {
                    listener.close();
                }
            });
            return listener.emitter();
        }
    }
}
