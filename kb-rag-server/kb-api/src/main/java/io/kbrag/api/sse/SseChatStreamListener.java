package io.kbrag.api.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.api.dto.RetrievalNodeResponse;
import io.kbrag.api.dto.ChatDiagnosticsResponse;
import io.kbrag.app.openapi.ChatDiagnostics;
import io.kbrag.app.openapi.ChatStreamListener;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.model.ChatCancellation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * Writes one chat stream as server sent events, requirement section 4.8.
 *
 * <p>Event names and payload shapes are the contract with the console and with external agents:
 * <pre>
 * message_delta -&gt; {"delta": "..."}
 * references    -&gt; {"references": [RetrievalNode, ...]}
 * done          -&gt; {"request_id": "...", "degraded": [...], "routed_kb_ids": [...]}
 * error         -&gt; {"code": "...", "message": "..."}
 * </pre>
 *
 * <p>The emitter is completed by exactly one terminal event. A write failure - a client that hung up mid answer
 * is the normal case, not an exception - completes the emitter and stops the stream instead of propagating into
 * the generation thread, which has no caller left to report to.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class SseChatStreamListener implements ChatStreamListener {

    /** Event name of one generated piece of the answer. */
    public static final String EVENT_MESSAGE_DELTA = "message_delta";

    /** Event name of the retrieved material. */
    public static final String EVENT_REFERENCES = "references";

    /** Event name of the successful terminal event. */
    public static final String EVENT_DONE = "done";

    /** Event name of the failed terminal event. */
    public static final String EVENT_ERROR = "error";

    /** 控制台预览的可选诊断事件，不是回答终态。 */
    public static final String EVENT_DIAGNOSTICS = "diagnostics";

    /** No server side timeout: the generation itself is bounded by the provider timeout. */
    private static final long NO_TIMEOUT = 0L;

    private final SseEmitter emitter;
    private final ChatCancellation cancellation = new ChatCancellation();
    private boolean closed;

    /** 构造连接；生产入口由工厂统一管理心跳和关闭。 */
    public SseChatStreamListener() {
        this(new SseEmitter(NO_TIMEOUT));
    }

    SseChatStreamListener(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(failure -> close());
    }

    @Override
    public ChatCancellation cancellation() {
        return cancellation;
    }

    /**
     * The emitter the controller returns.
     *
     * @return emitter of this stream
     */
    public SseEmitter emitter() {
        return emitter;
    }

    @Override
    public void onDelta(String delta) {
        send(EVENT_MESSAGE_DELTA, new DeltaEvent(delta));
    }

    @Override
    public void onReferences(List<RetrievalNodeView> references) {
        send(EVENT_REFERENCES, new ReferencesEvent(
                references.stream().map(RetrievalNodeResponse::from).toList()));
    }

    @Override
    public void onDiagnostics(ChatDiagnostics diagnostics) {
        send(EVENT_DIAGNOSTICS, ChatDiagnosticsResponse.from(diagnostics));
    }

    @Override
    public synchronized void onDone(String requestId, List<String> degraded, List<String> routedKbIds) {
        send(EVENT_DONE, new DoneEvent(requestId, degraded, routedKbIds));
        complete();
    }

    @Override
    public synchronized void onError(String code, String message) {
        send(EVENT_ERROR, new ErrorEvent(code, message));
        complete();
    }

    private synchronized void send(String event, Object payload) {
        send(SseEmitter.event().name(event).data(payload));
    }

    /** 静默生成期间发送注释，既保活也使断开的客户端能被及时识别。 */
    synchronized void heartbeat() {
        send(SseEmitter.event().comment("keep-alive"));
    }

    private synchronized void send(SseEmitter.SseEventBuilder event) {
        if (closed) {
            return;
        }
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // A disconnected client is the expected end of a stream, not a fault worth an error code.
            log.info("chat stream closed by the client");
            complete();
        } catch (Exception e) {
            log.error("chat stream event could not be written, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            closed = true;
            cancellation.cancel();
            emitter.completeWithError(e);
        }
    }

    private synchronized void complete() {
        if (!closed) {
            closed = true;
            cancellation.cancel();
            emitter.complete();
        }
    }

    /** 连接或异步提交失败时先终止上游，再释放传输资源。 */
    public void close() {
        cancellation.cancel();
        complete();
    }

    /**
     * Payload of a {@code message_delta} event.
     *
     * @param delta text fragment
     */
    private record DeltaEvent(String delta) {
    }

    /**
     * Payload of a {@code references} event.
     *
     * @param references retrieval nodes in rank order
     */
    private record ReferencesEvent(List<RetrievalNodeResponse> references) {
    }

    /**
     * Payload of a {@code done} event.
     *
     * @param requestId   correlation id
     * @param degraded    degradation markers
     * @param routedKbIds knowledge bases the retrieval stage searched
     */
    private record DoneEvent(@JsonProperty("request_id") String requestId, List<String> degraded,
                             @JsonProperty("routed_kb_ids") List<String> routedKbIds) {
    }

    /**
     * Payload of an {@code error} event.
     *
     * @param code    business error code
     * @param message safe message
     */
    private record ErrorEvent(String code, String message) {
    }
}
