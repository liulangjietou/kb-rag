package io.kbrag.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.model.GraphEntity;
import io.kbrag.domain.model.GraphRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates what the extraction model answered before any of it reaches the graph,
 * requirement section 4.4 "prompt injection protection, output hard validation".
 *
 * <p><b>The model output is untrusted input, twice over.</b> It is generated text, so it may not be JSON
 * at all; and it was generated from document content an outsider may have written, so it may name
 * anything. Neither risk is answered by a defensive check further down - a malformed answer accepted here
 * would be written into the graph and would only be noticed as a wrong retrieval result weeks later.
 * This class is therefore the single gate: what it returns is structurally valid by construction, and
 * everything else is rejected as a whole.
 *
 * <p><b>Rejection is per chunk, never per task.</b> A model that fumbles one passage out of a thousand
 * must not fail an extraction over a whole knowledge base, so the caller counts the rejection and moves
 * on. That is also why the rejection reason is logged here and not thrown: the count is the operator
 * visible signal, the reason is the diagnostic.
 *
 * <p>Three rules, all from the contract:
 * <ul>
 *   <li>the answer must parse as a JSON object carrying an {@code entities} array;</li>
 *   <li>an entity name must be non blank and at most {@value #MAX_ENTITY_NAME_LENGTH} characters -
 *       a longer "name" is a sentence the model failed to condense, and it would pollute the entity
 *       index it is merged into;</li>
 *   <li>both endpoints of a relation must appear in the entity list of the <em>same</em> answer, so the
 *       graph can never grow a node nothing was extracted for.</li>
 * </ul>
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class GraphExtractionParser {

    /** Longest accepted entity name. */
    public static final int MAX_ENTITY_NAME_LENGTH = 128;

    /** Category recorded when the model named none. */
    public static final String DEFAULT_ENTITY_TYPE = "unknown";

    /** Label recorded when the model named no relation type. */
    public static final String DEFAULT_RELATION_TYPE = "related_to";

    private static final String FIELD_ENTITIES = "entities";
    private static final String FIELD_RELATIONS = "relations";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_TARGET = "target";

    private static final char JSON_OBJECT_START = '{';
    private static final char JSON_OBJECT_END = '}';

    /** Longest accepted free text category, truncated rather than rejected: it is a label, not identity. */
    private static final int MAX_TYPE_LENGTH = 64;

    /**
     * Parses and validates one model answer.
     *
     * @param raw raw model answer, possibly wrapped in prose or a code fence
     * @return validated result, {@code null} when the answer must be skipped
     */
    public Result parse(String raw) {
        JsonNode root = readObject(raw);
        if (root == null) {
            log.info("graph extraction answer rejected, reason=not a json object");
            return null;
        }
        JsonNode entityNode = root.get(FIELD_ENTITIES);
        if (entityNode == null || !entityNode.isArray()) {
            // An object without the contracted entity array is not "an extraction that found nothing", it
            // is an answer of some other shape. Treating the two alike would silently turn a model that
            // stopped following the contract into a corpus that appears to contain no entity.
            log.info("graph extraction answer rejected, reason=no entities array");
            return null;
        }
        Map<String, GraphEntity> entities = new LinkedHashMap<>();
        for (JsonNode node : entityNode) {
            String name = text(node, FIELD_NAME);
            if (name == null) {
                continue;
            }
            if (name.length() > MAX_ENTITY_NAME_LENGTH) {
                log.info("graph extraction answer rejected, reason=entity name too long, length={}",
                        name.length());
                return null;
            }
            entities.putIfAbsent(name, new GraphEntity(name, typeOf(node, DEFAULT_ENTITY_TYPE)));
        }
        List<GraphRelation> relations = new ArrayList<>();
        for (JsonNode node : arrayOf(root, FIELD_RELATIONS)) {
            String source = text(node, FIELD_SOURCE);
            String target = text(node, FIELD_TARGET);
            if (source == null || target == null) {
                continue;
            }
            if (!entities.containsKey(source) || !entities.containsKey(target)) {
                // 丢这一条，不作废整个答案：目标是"图里不长出没有抽取依据的节点"，跳过越界关系即可达成，
                // 而整体拒绝会让同一段里已经抽对的实体和关系一起陪葬——上面缺 source/target 的分支
                // 本来就是这么处理的，两者同为"这条关系不可用"，没有理由区别对待。
                log.info("graph extraction relation dropped, reason=endpoint outside the entity list");
                continue;
            }
            relations.add(new GraphRelation(source, typeOf(node, DEFAULT_RELATION_TYPE), target));
        }
        return new Result(List.copyOf(entities.values()), List.copyOf(relations));
    }

    /**
     * Reads the JSON object out of a model answer, tolerating the prose and the code fence a model
     * adds even when it was told not to.
     *
     * <p>Tolerance stops there on purpose: the outermost braces are located, and what sits between them
     * must parse. Repairing the content itself - closing a quote, dropping a trailing comma - would mean
     * guessing what the model meant, and a guess written into the graph is indistinguishable from an
     * extraction that really happened.
     *
     * @param raw raw model answer
     * @return parsed object node, {@code null} when there is none
     */
    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            log.info("graph extraction answer rejected, reason=empty answer");
            return null;
        }
        int start = raw.indexOf(JSON_OBJECT_START);
        int end = raw.lastIndexOf(JSON_OBJECT_END);
        if (start < 0 || end <= start) {
            // 最常见的成因是生成预算耗尽把 JSON 截断在半路，此时根本没有收尾的花括号。
            // 长度一并打出来，便于对照 kb.graph.extract-max-tokens 判断是否又是被截断。
            log.info("graph extraction answer rejected, reason=no json object boundary, length={}", raw.length());
            return null;
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(raw.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                log.info("graph extraction answer rejected, reason=payload is not an object");
                return null;
            }
            return node;
        } catch (Exception e) {
            log.info("graph extraction answer rejected, reason=malformed json, detail={}", e.getMessage());
            return null;
        }
    }

    /**
     * Reads an array field, treating an absent or non array value as empty.
     *
     * @param root  answer object
     * @param field field name
     * @return array node, never {@code null}
     */
    private Iterable<JsonNode> arrayOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isArray() ? node : List.of();
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

    /**
     * Reads the free text category of an element.
     *
     * @param node        element node
     * @param defaultType category recorded when the model named none
     * @return category, truncated to {@value #MAX_TYPE_LENGTH} characters
     */
    private String typeOf(JsonNode node, String defaultType) {
        String type = text(node, FIELD_TYPE);
        if (type == null) {
            return defaultType;
        }
        return type.length() > MAX_TYPE_LENGTH ? type.substring(0, MAX_TYPE_LENGTH) : type;
    }

    /**
     * What one accepted answer contained.
     *
     * @param entities  distinct entities, first mention wins the category
     * @param relations relations whose endpoints are both in {@link #entities}
     */
    public record Result(List<GraphEntity> entities, List<GraphRelation> relations) {

        /**
         * Tells whether the answer named anything worth writing.
         *
         * @return {@code true} when no entity was extracted
         */
        public boolean isEmpty() {
            return entities.isEmpty();
        }
    }
}
