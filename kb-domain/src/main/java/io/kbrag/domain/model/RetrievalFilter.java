package io.kbrag.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Mandatory engine side filter of every retrieval call.
 *
 * <p>Both predicates are enforced by the pipeline and cannot be disabled through request
 * parameters: only chunks of a visible document version and only enabled chunks may be recalled.
 */
@Getter
@Builder
@ToString
public class RetrievalFilter {

    /** Knowledge base scope. */
    private final String kbId;

    /** Visible document version ids, the active versions when there is no version context. */
    private final List<String> documentVersionIds;

    /** Always {@code true} in M1, kept explicit so the predicate is visible at the call site. */
    private final boolean enabledOnly;
}
