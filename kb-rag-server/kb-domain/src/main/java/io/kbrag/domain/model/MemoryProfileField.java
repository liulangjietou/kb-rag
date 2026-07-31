package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One attribute definition of a profile rule, the M19 contract.
 *
 * <p>The in-memory shape of one element of the rule's {@code fields} JSON column; the Jackson
 * annotations bind the snake case keys the column and the API contract both use.
 *
 * @param name         attribute name, the key of the extracted profile object
 * @param description  what the extraction model should look for
 * @param initialValue value reported before anything was extracted, may be {@code null}
 * @author owlzhangfq@gmail.com
 */
public record MemoryProfileField(@JsonProperty("name") String name,
                                 @JsonProperty("description") String description,
                                 @JsonProperty("initial_value") String initialValue) {
}
