package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.chat.ChatImportView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Match preview of a chat import.
 *
 * <p>The token is part of the response because it is the input of the confirmation: the two calls are one
 * transaction from the user's point of view, and the second one has to act on exactly the plan the first one
 * displayed.
 *
 * @param uploadToken token identifying the staged upload, required by the confirmation
 * @param sessions    one entry per conversation found in the export
 * @param skipped     messages the parser dropped, per reason
 *
 * @author owlzhangfq@gmail.com
 */
public record ChatImportPreviewResponse(
        @JsonProperty("upload_token") String uploadToken,
        List<SessionView> sessions,
        Map<String, Integer> skipped) {

    /**
     * What the confirmation would do with one conversation.
     *
     * @param sessionId    conversation identifier of the source channel
     * @param sessionName  display name of the conversation
     * @param messageCount number of messages that would be imported
     * @param timeRange    first and last message time in epoch milliseconds
     * @param matchedDocId document the conversation already maps to, {@code null} when it is new
     * @param action       {@code CREATE} or {@code NEW_VERSION}
     */
    public record SessionView(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("session_name") String sessionName,
            @JsonProperty("message_count") int messageCount,
            @JsonProperty("time_range") TimeRange timeRange,
            @JsonProperty("matched_doc_id") String matchedDocId,
            String action) {
    }

    /**
     * Time span of a conversation.
     *
     * @param from send time of the first message, in epoch milliseconds
     * @param to   send time of the last message, in epoch milliseconds
     */
    public record TimeRange(Long from, Long to) {
    }

    /**
     * Maps an application view onto the transport shape.
     *
     * @param view application view
     * @return transport response
     */
    public static ChatImportPreviewResponse from(ChatImportView view) {
        List<SessionView> sessions = new ArrayList<>();
        for (ChatImportView.SessionMatch match : view.getSessions()) {
            sessions.add(new SessionView(match.getSessionId(), match.getSessionName(),
                    match.getMessageCount(), new TimeRange(match.getTimeFrom(), match.getTimeTo()),
                    match.getMatchedDocId(), match.getAction()));
        }
        return new ChatImportPreviewResponse(view.getUploadToken(), sessions, view.getSkipped());
    }
}
