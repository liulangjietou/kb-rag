package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One knowledge base an application version is linked to, together with its quota weight,
 * requirement section 4.7 "cross base fusion rules".
 *
 * <p><b>The weight is a quota weight, not a recall route weight.</b> It never touches a score: raw scores
 * of two knowledge bases may come from different embedding models and are not comparable, so weighting
 * them would produce a number that means nothing. It sizes how many candidates a base may contribute to
 * the shared rerank budget, which is the only place a preference between bases can be expressed without
 * inventing a comparison.
 *
 * @param kbId   knowledge base business id
 * @param weight quota weight, {@code null} or non positive reads as {@link #DEFAULT_WEIGHT}
 *
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KbRef(
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("weight") Integer weight) {

    /** Weight of a base the operator never weighted, and of a migrated single base snapshot. */
    public static final int DEFAULT_WEIGHT = 1;

    /**
     * Reference with the default weight.
     *
     * @param kbId knowledge base business id
     * @return reference weighted {@link #DEFAULT_WEIGHT}
     */
    public static KbRef of(String kbId) {
        return new KbRef(kbId, DEFAULT_WEIGHT);
    }

    /**
     * Weight the quota allocation actually uses.
     *
     * <p>A missing or non positive weight reads as the default rather than as zero: a base an operator
     * linked but forgot to weight must still be searched, and a zero quota would silently remove it.
     *
     * @return effective positive weight
     */
    public int effectiveWeight() {
        return weight == null || weight < DEFAULT_WEIGHT ? DEFAULT_WEIGHT : weight;
    }
}
