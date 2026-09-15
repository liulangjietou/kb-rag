package io.kbrag.infrastructure.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实本地 HTTP 流，避免把完整回答后的单次回调误认为逐段输出。 */
class DashScopeChatStreamingTest {

    @ParameterizedTest
    @ValueSource(strings = {"/gateway?api-version=fixture", "/gateway/?api-version=fixture",
            "/gateway?api-version=fixture&label=中文"})
    void shouldPreserveGatewayBasePathAndQueryInBothTransports(String basePath) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> queries = new CopyOnWriteArrayList<>();
        server.createContext("/gateway/chat/completions", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            JsonNode request = JsonUtil.parse(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), JsonNode.class);
            boolean stream = request.path("stream").asBoolean();
            byte[] body = (stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var config = new KbProperties.Chat();
            config.setApiKey("fixture-key");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
            var provider = new DashScopeChatProvider(config);
            assertEquals("answer", provider.complete(null, List.of(ChatMessage.user("question"))));
            List<String> deltas = new CopyOnWriteArrayList<>();
            provider.stream(null, List.of(ChatMessage.user("question")), deltas::add);
            assertEquals(List.of("answer"), deltas);
            assertEquals(2, queries.size());
            assertEquals(queries.get(0), queries.get(1));
            assertTrue(queries.get(0).startsWith("api-version=fixture"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldDeliverFirstDeltaBeforeTheProviderFinishes() throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch finishProvider = new CountDownLatch(1);
        CountDownLatch firstReceived = new CountDownLatch(1);
        AtomicBoolean requestedStreaming = new AtomicBoolean();
        List<String> deltas = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> generation = null;
        server.createContext("/chat/completions", exchange -> {
            JsonNode payload = JsonUtil.parse(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), JsonNode.class);
            requestedStreaming.set(payload.path("stream").asBoolean());
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("data: {\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                body.flush();
                firstWritten.countDown();
                if (finishProvider.await(10, TimeUnit.SECONDS)) {
                    body.write(("data: {\"choices\":[{\"delta\":{\"content\":\"第二段\"}}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            KbProperties.Chat config = new KbProperties.Chat();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setApiKey("fixture-key");
            DashScopeChatProvider provider = new DashScopeChatProvider(config);
            generation = executor.submit(() -> provider.stream("系统要求", List.of(ChatMessage.user("问题")), delta -> {
                deltas.add(delta);
                firstReceived.countDown();
            }));
            assertTrue(firstWritten.await(5, TimeUnit.SECONDS), "模型替身应已输出首段");
            assertTrue(firstReceived.await(1, TimeUnit.SECONDS), "上游尚未完成时应已向调用方输出首段");
            assertTrue(requestedStreaming.get(), "请求应显式启用上游流式输出");
            assertEquals(List.of("第一段"), deltas);
            finishProvider.countDown();
            generation.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("第一段", "第二段"), deltas);
        } finally {
            finishProvider.countDown();
            if (generation != null) generation.cancel(true);
            server.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
