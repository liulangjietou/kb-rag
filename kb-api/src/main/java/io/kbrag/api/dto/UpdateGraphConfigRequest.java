package io.kbrag.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * New graph switch of a knowledge base, requirement section 4.9.
 *
 * <p>One field on purpose. Everything else the graph needs - the hop count, the match limit, the
 * extraction concurrency - is a deployment knob rather than a per base decision, and exposing it here
 * would let two knowledge bases of the same deployment disagree about what one hop means.
 *
 * @param enabled new switch value
 *
 * @author owlzhangfq@gmail.com
 */
public record UpdateGraphConfigRequest(@NotNull Boolean enabled) {
}
