package io.kbrag.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * One parent chunk together with the children it was split into.
 *
 * <p>Both levels are produced in a single pass so a child can never end up orphaned or pointing at a
 * parent whose text it is not actually part of.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@AllArgsConstructor
@ToString
public class ParentChunk {

    /** Parent level chunk, the unit returned to the caller. */
    private final SplitChunk parent;

    /** Child level chunks, the units the search engines index. */
    private final List<SplitChunk> children;
}
