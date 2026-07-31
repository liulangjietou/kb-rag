package io.kbrag.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.enums.MemoryEventType;
import io.kbrag.domain.model.MemoryCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates what the memory extraction model answered before any of it is written, the same single
 * gate discipline as {@link GraphExtractionParser}: the answer is generated text produced from end
 * user conversation, untrusted twice over, and everything this class returns is structurally valid
 * by construction.
 *
 * <p>Rejection granularity differs by failure. A malformed answer as a whole yields the empty
 * result - a memory write that silently drops the whole conversation is recoverable, the caller
 * simply reports zero extracted memories. A single bad element (blank content, oversized content,
 * an UPDATE pointing outside the entity's own memories) is dropped alone, because the other
 * elements of the same answer are independent facts and there is no reason to bury them with it.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class MemoryExtractionParser {

    /** Longest accepted memory content; a longer one is a transcript, not a memory. */
    public static final int MAX_CONTENT_LENGTH = 2000;

    /** Longest accepted profile attribute value. */
    public static final int MAX_ATTRIBUTE_LENGTH = 512;

    /** Most memories accepted out of one answer; the tail beyond it is dropped. */
    public static final int MAX_MEMORIES_PER_ANSWER = 20;

    private static final String FIELD_MEMORIES = "memories";
    private static final String FIELD_EVENT = "event";
    private static final String FIELD_NODE_ID = "node_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_ATTRIBUTES = "attributes";

    private static final char JSON_OBJECT_START = '{';
    private static final char JSON_OBJECT_END = '}';

    /**
     * Parses one fragment extraction answer.
     *
     * @param raw          raw model answer, possibly wrapped in prose or a code fence
     * @param knownNodeIds node ids of the entity's own memories, the only legal UPDATE targets
     * @param allowUpdate  whether the rule permitted UPDATE events at all
     * @return validated commands, empty when the answer must be skipped
     */
    public List<MemoryCommand> parseFragments(String raw, Set<String> knownNodeIds, boolean allowUpdate) {
        JsonNode root = readObject(raw);
        if (root == null) {
            return List.of();
        }
        JsonNode memories = root.get(FIELD_MEMORIES);
        if (memories == null || !memories.isArray()) {
            log.info("memory extraction answer rejected, reason=no memories array");
            return List.of();
        }
        List<MemoryCommand> commands = new ArrayList<>();
        for (JsonNode node : memories) {
            if (commands.size() >= MAX_MEMORIES_PER_ANSWER) {
                log.info("memory extraction answer truncated, reason=too many memories, kept={}",
                        MAX_MEMORIES_PER_ANSWER);
                break;
            }
            String content = text(node, FIELD_CONTENT);
            if (content == null) {
                continue;
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                log.info("memory extraction element dropped, reason=content too long, length={}",
                        content.length());
                continue;
            }
            MemoryEventType event = eventOf(node);
            if (event == MemoryEventType.UPDATE) {
                String nodeId = text(node, FIELD_NODE_ID);
                if (!allowUpdate || nodeId == null || !knownNodeIds.contains(nodeId)) {
                    // An UPDATE aimed at nothing the entity owns would either fail or, worse, hit
                    // another entity's memory; the fact itself is still worth keeping, so it is
                    // demoted to an ADD rather than dropped.
                    log.info("memory extraction update demoted to add, reason=unknown or forbidden target");
                    commands.add(new MemoryCommand(MemoryEventType.ADD, null, content));
                    continue;
                }
                commands.add(new MemoryCommand(MemoryEventType.UPDATE, nodeId, content));
                continue;
            }
            commands.add(new MemoryCommand(MemoryEventType.ADD, null, content));
        }
        return commands;
    }

    /**
     * Parses one profile extraction answer.
     *
     * @param raw           raw model answer
     * @param allowedFields field names the rule defines, the only keys accepted
     * @return extracted attributes in answer order, empty when the answer must be skipped
     */
    public Map<String, String> parseProfile(String raw, Set<String> allowedFields) {
        JsonNode root = readObject(raw);
        if (root == null) {
            return Map.of();
        }
        JsonNode attributes = root.get(FIELD_ATTRIBUTES);
        if (attributes == null || !attributes.isObject()) {
            log.info("profile extraction answer rejected, reason=no attributes object");
            return Map.of();
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        attributes.fields().forEachRemaining(entry -> {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!allowedFields.contains(name)) {
                // A key outside the rule is the model inventing a field; accepting it would let the
                // extraction grow the schema the operator defined.
                log.info("profile extraction attribute dropped, reason=field not in rule");
                return;
            }
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isValueNode() || valueNode.isNull()) {
                return;
            }
            String value = valueNode.asText().trim();
            if (value.isEmpty() || value.length() > MAX_ATTRIBUTE_LENGTH) {
                return;
            }
            extracted.put(name, value);
        });
        return extracted;
    }

    /**
     * Reads the JSON object out of a model answer, tolerating surrounding prose and code fences
     * but never repairing the content itself.
     *
     * @param raw raw model answer
     * @return parsed object node, {@code null} when there is none
     */
    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            log.info("memory extraction answer rejected, reason=empty answer");
            return null;
        }
        int start = raw.indexOf(JSON_OBJECT_START);
        int end = raw.lastIndexOf(JSON_OBJECT_END);
        if (start < 0 || end <= start) {
            log.info("memory extraction answer rejected, reason=no json object boundary, length={}",
                    raw.length());
            return null;
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(raw.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                log.info("memory extraction answer rejected, reason=payload is not an object");
                return null;
            }
            return node;
        } catch (Exception e) {
            log.info("memory extraction answer rejected, reason=malformed json, detail={}", e.getMessage());
            return null;
        }
    }

    /**
     * Reads the event literal, treating anything that is not an explicit UPDATE as ADD.
     *
     * @param node element node
     * @return event type
     */
    private MemoryEventType eventOf(JsonNode node) {
        String literal = text(node, FIELD_EVENT);
        if (literal != null && MemoryEventType.UPDATE.name().equalsIgnoreCase(literal)) {
            return MemoryEventType.UPDATE;
        }
        return MemoryEventType.ADD;
    }

    /**
     * Reads a trimmed non blank text field.
     *
     * @param node  element node
     * @param field field name
     * @return trimmed value, {@code null} when missing, blank or not textual
     */
    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
