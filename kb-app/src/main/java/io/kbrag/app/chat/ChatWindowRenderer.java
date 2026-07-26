package io.kbrag.app.chat;

import io.kbrag.domain.model.ChatWindow;
import io.kbrag.domain.model.ParsedChatFile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders an aggregation window as the text that gets indexed.
 *
 * <p>Format: {@code [time] sender: content}, one message per line. The sender and the time stay inside the
 * text rather than only in the metadata, because a retrieved window has to be readable on its own: an
 * answer built from a chat log is unusable if the reader cannot tell who said what.
 *
 * <p>The timestamp is rendered in the server time zone. A chat export carries local wall clock time in
 * practice, and converting to UTC would make every line disagree with what the user remembers seeing.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ChatWindowRenderer {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String UNKNOWN_TIME = "unknown time";
    private static final String UNKNOWN_SENDER = "unknown";

    /**
     * Renders one window.
     *
     * @param window aggregation window
     * @return one line per message
     */
    public String render(ChatWindow window) {
        StringBuilder text = new StringBuilder();
        for (ParsedChatFile.ChatMessageRecord message : window.getMessages()) {
            text.append('[').append(formatTime(message.getSendTime())).append("] ")
                    .append(sender(message)).append(": ")
                    .append(message.getContent() == null ? "" : message.getContent())
                    .append('\n');
        }
        return text.toString().trim();
    }

    private String formatTime(Long epochMillis) {
        if (epochMillis == null) {
            return UNKNOWN_TIME;
        }
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private String sender(ParsedChatFile.ChatMessageRecord message) {
        return message.getSender() == null || message.getSender().isBlank()
                ? UNKNOWN_SENDER : message.getSender();
    }
}
