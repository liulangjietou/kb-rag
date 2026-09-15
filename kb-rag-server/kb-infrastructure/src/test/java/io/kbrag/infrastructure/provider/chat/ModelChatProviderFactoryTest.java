package io.kbrag.infrastructure.provider.chat;

import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实等待中的 HTTP 请求验证版本固定模型继承部署的生成超时。 */
class ModelChatProviderFactoryTest {

    @Test
    void shouldPreserveTheConfiguredGenerationDeadlineForVersionPinnedModels() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var worker = Executors.newSingleThreadExecutor();
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            KbProperties properties = new KbProperties();
            properties.getChat().setApiKey("fixture-key");
            properties.getChat().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getChat().setGenerateTimeoutMs(500);
            var provider = new ModelChatProviderFactory(properties).forModel("pinned-model");
            var call = worker.submit(() -> provider.stream(null, List.of(ChatMessage.user("问题")), delta -> { }));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> call.get(2, TimeUnit.SECONDS), "应在配置预算内结束，不能悄悄恢复为默认 60 秒");
            assertInstanceOf(ProviderException.class, failure.getCause());
        } finally {
            release.countDown();
            server.stop(0);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
