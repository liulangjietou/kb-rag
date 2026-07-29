package io.kbrag.domain.service;

import io.kbrag.domain.model.MetadataRule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the metadata rules of a knowledge base to chunk text, the M14 contract section 3.2.
 *
 * <p>A pure function of rules and text: no repository, no engine, no clock. The pipeline prepares
 * the rule set once per document so a pattern is compiled once instead of once per chunk, then runs
 * the prepared set over every chunk it persists.
 *
 * <p>A rule that yields nothing writes no key at all - an absent key is how the engine side filter
 * distinguishes "not extracted" from "extracted empty", so an empty placeholder would make every
 * chunk match every equality filter on that key.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class MetadataRuleExtractor {

    /**
     * A rule with its pattern compiled, ready to run over any number of chunks.
     *
     * @param rule    declared rule
     * @param pattern compiled pattern, {@code null} for every type but {@code regex}
     */
    public record PreparedRule(MetadataRule rule, Pattern pattern) {
    }

    /**
     * Compiles the rule set once so the per chunk extraction never compiles anything.
     *
     * <p>A pattern that no longer compiles is skipped rather than failing the build: the configuration
     * gate rejects it at write time, so hitting one here means the stored configuration predates a
     * validation change, and losing one metadata key is the better outcome than losing the document.
     *
     * @param rules declared rules, may be empty
     * @return prepared rules, empty when nothing is declared
     */
    public List<PreparedRule> prepare(List<MetadataRule> rules) {
        if (CollectionUtils.isEmpty(rules)) {
            return List.of();
        }
        List<PreparedRule> prepared = new ArrayList<>(rules.size());
        for (MetadataRule rule : rules) {
            if (rule == null || rule.getKey() == null || rule.getType() == null) {
                continue;
            }
            if (MetadataRule.TYPE_REGEX.equals(rule.getType())) {
                try {
                    prepared.add(new PreparedRule(rule, Pattern.compile(rule.getPattern())));
                } catch (Exception e) {
                    log.info("skip metadata rule with uncompilable pattern, key={}", rule.getKey());
                }
                continue;
            }
            prepared.add(new PreparedRule(rule, null));
        }
        return prepared;
    }

    /**
     * Runs the prepared rules over one chunk text.
     *
     * @param prepared prepared rules, may be empty
     * @param content  chunk text
     * @return extracted values by rule key, insertion ordered, empty when nothing matched
     */
    public Map<String, Object> extract(List<PreparedRule> prepared, String content) {
        if (CollectionUtils.isEmpty(prepared) || content == null) {
            return Map.of();
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (PreparedRule entry : prepared) {
            Object value = valueOf(entry, content);
            if (value != null) {
                extracted.put(entry.rule().getKey(), value);
            }
        }
        return extracted;
    }

    private Object valueOf(PreparedRule entry, String content) {
        MetadataRule rule = entry.rule();
        if (MetadataRule.TYPE_CONSTANT.equals(rule.getType())) {
            return truncate(rule.getValue());
        }
        if (MetadataRule.TYPE_REGEX.equals(rule.getType())) {
            return regexValue(entry.pattern(), content);
        }
        if (MetadataRule.TYPE_KEYWORD_MATCH.equals(rule.getType())) {
            return keywordValue(rule.getKeywords(), content);
        }
        return null;
    }

    /**
     * First capture group of the first match, the whole match when the pattern declares no group.
     *
     * @param pattern compiled pattern
     * @param content chunk text
     * @return captured value, {@code null} when the pattern does not match
     */
    private String regexValue(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String captured = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
        return truncate(captured);
    }

    /**
     * Subset of the vocabulary the chunk contains, in vocabulary order.
     *
     * @param keywords vocabulary
     * @param content  chunk text
     * @return matched words, {@code null} when none matched
     */
    private List<String> keywordValue(List<String> keywords, String content) {
        if (CollectionUtils.isEmpty(keywords)) {
            return null;
        }
        List<String> matched = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && content.contains(keyword)) {
                matched.add(keyword);
            }
        }
        return matched.isEmpty() ? null : matched;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > MetadataRule.MAX_VALUE_LENGTH
                ? value.substring(0, MetadataRule.MAX_VALUE_LENGTH) : value;
    }
}
