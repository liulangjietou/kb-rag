package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One operator declared metadata extraction rule of the {@code metadata_rules} block inside
 * {@code t_kb_knowledge_base.index_config}, the M14 contract section 3.1.
 *
 * <p>Three rule types exist: {@code constant} stamps a fixed value on every chunk, {@code regex}
 * captures a value out of the chunk text, {@code keyword_match} records which words of a vocabulary
 * the chunk contains. The rule set feeds the chunk fingerprint, so editing it marks the affected
 * documents configuration stale instead of silently leaving old and new metadata side by side.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataRule {

    /** Rule type: fixed value. */
    public static final String TYPE_CONSTANT = "constant";

    /** Rule type: first capture group of a pattern, or the whole match when there is no group. */
    public static final String TYPE_REGEX = "regex";

    /** Rule type: subset of a vocabulary found in the chunk text. */
    public static final String TYPE_KEYWORD_MATCH = "keyword_match";

    /** Shape every metadata key has to have, shared with the custom filter of the retrieval side. */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");

    /** Rules a knowledge base may declare at most. */
    public static final int MAX_RULES = 10;

    /** Longest accepted regular expression source. */
    public static final int MAX_PATTERN_LENGTH = 64;

    /** Largest accepted vocabulary. */
    public static final int MAX_KEYWORDS = 50;

    /** Longest accepted vocabulary word. */
    public static final int MAX_KEYWORD_LENGTH = 32;

    /** Longest stored extracted value; anything beyond is cut, never rejected. */
    public static final int MAX_VALUE_LENGTH = 256;

    /** Metadata key the extracted value is stored under. */
    private String key;

    /** Rule type, one of the three type literals. */
    private String type;

    /** Fixed value of a {@code constant} rule. */
    private String value;

    /** Pattern source of a {@code regex} rule. */
    private String pattern;

    /** Vocabulary of a {@code keyword_match} rule. */
    private List<String> keywords;

    /**
     * Renders the rule into the canonical form the chunk fingerprint digests.
     *
     * @return stable text representation of every semantic carrying field
     */
    public String fingerprintSegment() {
        return key + ":" + type + ":" + nullToEmpty(value) + ":" + nullToEmpty(pattern) + ":"
                + (CollectionUtils.isEmpty(keywords) ? "" : String.join(",", keywords));
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
