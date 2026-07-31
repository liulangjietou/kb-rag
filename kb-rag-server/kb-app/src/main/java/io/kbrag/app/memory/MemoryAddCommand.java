package io.kbrag.app.memory;

import io.kbrag.domain.model.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * One AddMemory call as the application layer receives it, the M19 contract.
 *
 * <p>The library is deliberately absent: it comes from the authenticated key, never from the
 * payload, so a caller cannot name a library its credential is not bound to.
 *
 * @param userId         memory entity id chosen by the caller
 * @param messages       conversation to extract from, may be empty
 * @param customContent  verbatim content to write without extraction, may be {@code null}
 * @param fragmentRuleId fragment rule to apply, {@code null} takes the library's builtin default
 * @param profileRuleId  profile rule to extract under, {@code null} skips profile extraction
 * @param metaData       caller supplied metadata attached to every node this call creates
 * @author owlzhangfq@gmail.com
 */
public record MemoryAddCommand(String userId, List<ChatMessage> messages, String customContent,
                               String fragmentRuleId, String profileRuleId,
                               Map<String, Object> metaData) {
}
