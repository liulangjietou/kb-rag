package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat session (room/contact) and its ordered messages.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    private String sessionId;

    private String sessionName;

    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();
}
