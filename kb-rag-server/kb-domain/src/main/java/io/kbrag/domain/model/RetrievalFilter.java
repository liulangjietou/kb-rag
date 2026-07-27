package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Engine side filter of every retrieval call.
 *
 * <p>The first three predicates are mandatory: they are built by the pipeline and cannot be
 * influenced by request parameters, so only chunks of a visible document version and only enabled
 * chunks may ever be recalled. The optional {@link #metadataFilter} is the only caller controlled
 * part, and it can only narrow the result further.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString
public class RetrievalFilter {

    /** Knowledge base scope. */
    private final String kbId;

    /** Visible document version ids, the active versions when there is no version context. */
    private final List<String> documentVersionIds;

    /** Always {@code true}, kept explicit so the predicate is visible at the call site. */
    private final boolean enabledOnly;

    /** Caller supplied narrowing predicate, {@code null} when the caller supplied none. */
    private final MetadataFilter metadataFilter;
}
