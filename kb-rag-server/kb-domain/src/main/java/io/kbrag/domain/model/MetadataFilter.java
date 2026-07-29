package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.List;
import java.util.Map;

/**
 * Optional narrowing predicate supplied by the caller.
 *
 * <p>Every dimension here is part of the fixed engine side field set, so the predicate is always
 * pushed down to the engine and never applied after recall: filtering afterwards would silently
 * shrink the candidate set below {@code recall_top_k} and make the fusion stage compare lists of
 * different depth.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class MetadataFilter {

    /** Document tag ids, matched as "any of". */
    private final List<String> tagIds;

    /** Chat session id. */
    private final String sessionId;

    /** Chat message sender. */
    private final String sender;

    /** Inclusive lower bound of the message timestamp in epoch milliseconds. */
    private final Long msgTimeFrom;

    /** Inclusive upper bound of the message timestamp in epoch milliseconds. */
    private final Long msgTimeTo;

    /**
     * Equality predicates on operator extracted metadata, the M14 contract section 3.3, keyed by the
     * raw rule key and combined with AND semantics. A {@code keyword_match} key matches when the
     * stored array contains the value.
     */
    private final Map<String, String> custom;

    /**
     * Tells whether this filter carries at least one predicate.
     *
     * @return {@code true} when the filter narrows the candidate set
     */
    public boolean isEmpty() {
        return CollectionUtils.isEmpty(tagIds)
                && isBlank(sessionId)
                && isBlank(sender)
                && msgTimeFrom == null
                && msgTimeTo == null
                && MapUtils.isEmpty(custom);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
