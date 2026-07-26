package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Character offsets a chunk text is cut at.
 *
 * <p>Whether the offsets are ascending and inside the text is decided by the application service: the
 * upper bound is the length of the stored text, which the payload does not carry.
 *
 * @param splitOffsets zero based character offsets, one fewer than the number of parts produced
 *
 * @author owlzhangfq@gmail.com
 */
public record SplitChunkRequest(
        @JsonProperty("split_offsets") @NotEmpty List<Integer> splitOffsets) {
}
