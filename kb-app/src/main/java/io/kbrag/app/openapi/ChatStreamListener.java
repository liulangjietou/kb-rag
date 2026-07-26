package io.kbrag.app.openapi;

import io.kbrag.app.retrieval.RetrievalNodeView;

import java.util.List;

/**
 * Receiver of one streamed chat call, requirement section 4.8 "server sent events:
 * {@code message_delta} / {@code references} / {@code done} / {@code error}".
 *
 * <p>An interface rather than the transport type so the generation logic has no knowledge of server sent
 * events: the same orchestration serves the open endpoint, the console preview and a future transport, and the
 * event contract is enforced by the order the orchestration calls these methods in - deltas, then references,
 * then exactly one terminal event.
 *
 * @author owlzhangfq@gmail.com
 */
public interface ChatStreamListener {

    /**
     * One generated piece of the answer.
     *
     * @param delta text fragment, in order
     */
    void onDelta(String delta);

    /**
     * The retrieved material the answer was generated from, sent before the terminal event.
     *
     * @param references retrieval nodes in rank order
     */
    void onReferences(List<RetrievalNodeView> references);

    /**
     * Terminal event of a successful stream.
     *
     * @param requestId   correlation id of the call
     * @param degraded    degradation markers of the retrieval stage
     * @param routedKbIds knowledge bases the retrieval stage searched, requirement section 4.9
     */
    void onDone(String requestId, List<String> degraded, List<String> routedKbIds);

    /**
     * Terminal event of a failed stream.
     *
     * @param code    business error code
     * @param message safe message, never a stack trace
     */
    void onError(String code, String message);
}
