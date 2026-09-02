package io.kbrag.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured chat parse result returned on success.
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatParseData {

    @Builder.Default
    private List<ChatSession> sessions = new ArrayList<>();

    @Builder.Default
    private ChatSkippedStats skipped = new ChatSkippedStats();
}
