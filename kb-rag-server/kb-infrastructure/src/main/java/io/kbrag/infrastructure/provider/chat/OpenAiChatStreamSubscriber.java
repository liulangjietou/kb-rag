package io.kbrag.infrastructure.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import io.kbrag.infrastructure.provider.ModelUsageSupport;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** 解析 OpenAI 兼容聊天 SSE；按字节分行，跨网络块的中文和 CRLF 均不会被截断。 */
final class OpenAiChatStreamSubscriber implements HttpResponse.BodySubscriber<ModelTokenUsage> {

    private static final int MAX_LINE_BYTES = 65_536;
    private static final int MAX_EVENT_CHARS = 1_048_576;
    private static final int MAX_ERROR_BYTES = 4_096;
    private static final String DONE = "[DONE]";
    private static final String STAGE = "chat";

    private final int status;
    private final Consumer<String> onDelta;
    private final CompletableFuture<ModelTokenUsage> body = new CompletableFuture<>();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private volatile Flow.Subscription subscription;
    private volatile ModelTokenUsage usage = ModelTokenUsage.unknown();
    private boolean afterCarriageReturn;
    private boolean firstLine = true;

    OpenAiChatStreamSubscriber(int status, Consumer<String> onDelta) {
        this.status = status;
        this.onDelta = onDelta;
    }

    @Override
    public CompletionStage<ModelTokenUsage> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        if (body.isDone()) {
            incoming.cancel();
        } else {
            incoming.request(1);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        try {
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining() && !body.isDone()) {
                    int value = buffer.get() & 0xff;
                    if (status >= 300) {
                        line.write(value);
                        if (line.size() == MAX_ERROR_BYTES) {
                            reject();
                        }
                    } else {
                        accept(value);
                    }
                }
            }
            if (!body.isDone()) {
                subscription.request(1);
            }
        } catch (RuntimeException failure) {
            abort(failure);
        }
    }

    @Override
    public void onError(Throwable failure) {
        abort(failure);
    }

    @Override
    public void onComplete() {
        if (status >= 300) {
            reject();
        } else if (!body.isDone()) {
            // EOF 不派发残缺帧，也不把已收到的部分答案当作完成。
            abort(invalidResponse("chat stream ended before the terminal frame"));
        }
    }

    ModelTokenUsage usage() {
        return usage;
    }

    void abort(Throwable failure) {
        body.completeExceptionally(failure);
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }

    private void accept(int value) {
        if (afterCarriageReturn && value == '\n') {
            afterCarriageReturn = false;
            return;
        }
        afterCarriageReturn = value == '\r';
        if (value == '\r' || value == '\n') {
            acceptLine();
        } else {
            if (line.size() >= MAX_LINE_BYTES) {
                throw invalidResponse("chat stream line exceeds the size limit");
            }
            line.write(value);
        }
    }

    private void acceptLine() {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (firstLine) {
            firstLine = false;
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
        }
        if (text.isEmpty()) {
            if (!data.isEmpty()) {
                String event = data.substring(0, data.length() - 1);
                data.setLength(0);
                acceptEvent(event);
            }
            return;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        if (!"data".equals(field)) {
            return;
        }
        String value = colon < 0 ? "" : text.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if (data.length() + value.length() + 1 > MAX_EVENT_CHARS) {
            throw invalidResponse("chat stream event exceeds the size limit");
        }
        data.append(value).append('\n');
    }

    private void acceptEvent(String event) {
        if (DONE.equals(event)) {
            body.complete(usage);
            subscription.cancel();
            return;
        }
        JsonNode root;
        try {
            root = JsonUtil.parse(event, JsonNode.class);
        } catch (RuntimeException failure) {
            // 不把上游正文或其中的提示词写入异常信息和日志。
            throw invalidResponse("chat stream carries malformed JSON");
        }
        if (root == null || !root.isObject() || root.hasNonNull("error")) {
            throw invalidResponse("chat stream carries an invalid response or provider error");
        }
        ModelTokenUsage observed = ModelUsageSupport.usageOf(event);
        if (observed.known()) {
            usage = observed;
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) {
            throw invalidResponse("chat stream carries no choice array");
        }
        if (!choices.isEmpty()) {
            JsonNode content = choices.get(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                if (!content.isTextual()) {
                    throw invalidResponse("chat stream carries non-text content");
                }
                if (!content.asText().isEmpty()) {
                    onDelta.accept(content.asText());
                }
            }
        }
    }

    private void reject() {
        abort(DashScopeHttp.classify(DashScopeChatProvider.PROVIDER_NAME, STAGE,
                status, line.toString(StandardCharsets.UTF_8)));
    }

    private ProviderException invalidResponse(String message) {
        return new ProviderException(DashScopeChatProvider.PROVIDER_NAME, ProviderErrorType.UNKNOWN, message);
    }
}
