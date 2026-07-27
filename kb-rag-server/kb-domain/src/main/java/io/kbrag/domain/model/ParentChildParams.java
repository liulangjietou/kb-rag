package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Parent child splitting parameters, persisted inside the knowledge base index configuration.
 *
 * <p>Why two levels. A chunk has to be small to be recalled precisely and large to be understood by
 * the model that reads it, and those two sizes cannot be the same value. Splitting twice separates
 * the concerns: the small child is the unit the engines index and score, the large parent is the
 * unit the caller receives.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParentChildParams {

    /** Contract default parent size in estimated tokens. */
    public static final int DEFAULT_PARENT_MAX_TOKENS = 1200;

    /** Contract default child size in estimated tokens. */
    public static final int DEFAULT_CHILD_MAX_TOKENS = 400;

    /** Contract default child overlap in estimated tokens. */
    public static final int DEFAULT_CHILD_OVERLAP = 50;

    /** {@code false} keeps the single level behaviour where a chunk is both recall and answer unit. */
    private boolean enabled;

    /** Maximum estimated tokens of a parent chunk. */
    @JsonProperty("parent_max_tokens")
    private int parentMaxTokens = DEFAULT_PARENT_MAX_TOKENS;

    /** Maximum estimated tokens of a child chunk. */
    @JsonProperty("child_max_tokens")
    private int childMaxTokens = DEFAULT_CHILD_MAX_TOKENS;

    /** Overlap in estimated tokens replayed between two children of the same parent. */
    @JsonProperty("child_overlap")
    private int childOverlap = DEFAULT_CHILD_OVERLAP;

    /**
     * Builds the disabled default.
     *
     * @return parameters with the feature switched off
     */
    public static ParentChildParams disabled() {
        return new ParentChildParams();
    }

    /**
     * Fingerprint contribution of these parameters, part of the split fingerprint so a change marks
     * the affected documents as stale.
     *
     * @return stable textual form
     */
    public String fingerprintSegment() {
        if (!enabled) {
            return "parent_child=off";
        }
        return "parent_child=on:" + parentMaxTokens + ":" + childMaxTokens + ":" + childOverlap;
    }
}
