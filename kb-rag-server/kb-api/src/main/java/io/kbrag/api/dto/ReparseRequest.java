package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.domain.model.CleanRules;

/**
 * Optional body of a re-parse: an experimental rule set to try on this preview only.
 *
 * <p>The rules are deliberately not written back into the knowledge base configuration. Trying a watermark
 * expression on one document must not mark every other document of the knowledge base as stale, which is
 * exactly what saving the rules would do.
 *
 * @param cleanRules rules to apply to this preview, {@code null} keeps the stored ones
 *
 * @author owlzhangfq@gmail.com
 */
public record ReparseRequest(@JsonProperty("clean_rules") CleanRules cleanRules) {
}
