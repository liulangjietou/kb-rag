package io.kbrag.infrastructure.provider.chat;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 聊天增量传输与计量边界；超时覆盖响应头和整个响应体，不只等待首个字节。 */
final class ChatStreamHttp {

    private final KbProperties.Chat config;
    private final HttpClient client;
    private final ModelCallMeter meter;

    ChatStreamHttp(KbProperties.Chat config, ModelCallMeter meter) {
        this.config = config;
        this.meter = meter;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getTimeoutMs())).build();
    }

    void stream(Map<String, Object> payload, ModelCallSpec spec,
                Consumer<String> onDelta, ChatCancellation cancellation) {
        cancellation.throwIfCancelled();
        String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMillis(config.getGenerateTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload)))
                .build();
        ModelCallTicket ticket = meter.reserve(spec);
        AtomicReference<OpenAiChatStreamSubscriber> subscriber = new AtomicReference<>();
        AtomicBoolean rejected = new AtomicBoolean();
        CompletableFuture<HttpResponse<ModelTokenUsage>> response = null;
        ModelTokenUsage usage;
        try {
            cancellation.throwIfCancelled();
            response = client.sendAsync(request, information -> {
                rejected.set(information.statusCode() >= 300);
                OpenAiChatStreamSubscriber stream = new OpenAiChatStreamSubscriber(information.statusCode(), delta -> {
                    cancellation.throwIfCancelled();
                    onDelta.accept(delta);
                });
                subscriber.set(stream);
                if (cancellation.isCancelled()) {
                    stream.abort(new CancellationException("Chat generation cancelled"));
                }
                return stream;
            });
            CompletableFuture<?> cancellableResponse = response;
            try (ChatCancellation.Registration ignored = cancellation.onCancel(() -> {
                cancellableResponse.cancel(true);
                OpenAiChatStreamSubscriber stream = subscriber.get();
                if (stream != null) {
                    stream.abort(new CancellationException("Chat generation cancelled"));
                }
            })) {
                usage = response.get(config.getGenerateTimeoutMs(), TimeUnit.MILLISECONDS).body();
                cancellation.throwIfCancelled();
            }
        } catch (Exception failure) {
            RuntimeException cause = translated(failure, cancellation);
            OpenAiChatStreamSubscriber stream = subscriber.get();
            if (response != null) {
                response.cancel(true);
            }
            if (stream != null) {
                stream.abort(cause);
            }
            // 请求未发出或明确被拒绝时才释放；断网、取消和残缺响应可能已产生费用。
            if (response == null || rejected.get()) {
                meter.fail(ticket, cause);
            } else {
                meter.incomplete(ticket, stream == null ? ModelTokenUsage.unknown() : stream.usage(), cause);
            }
            throw cause;
        }
        // 结算失败留给预约回收处理，不能再次当作上游失败释放额度。
        meter.succeed(ticket, usage);
    }

    private RuntimeException translated(Exception failure, ChatCancellation cancellation) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (cancellation.isCancelled() || cause instanceof CancellationException
                || cause instanceof InterruptedException) {
            return new CancellationException("Chat generation cancelled");
        }
        if (cause instanceof ProviderException providerException) {
            return providerException;
        }
        String message = cause instanceof TimeoutException
                ? "chat generation exceeded its time budget" : "chat stream transport failed";
        return new ProviderException(DashScopeChatProvider.PROVIDER_NAME,
                ProviderErrorType.NETWORK_UNREACHABLE, message, cause);
    }
}
