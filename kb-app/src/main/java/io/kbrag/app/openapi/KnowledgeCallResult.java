package io.kbrag.app.openapi;

import io.kbrag.app.retrieval.AppliedInfo;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.domain.enums.TargetStage;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Outcome of one open API call, shared by the search and the chat endpoint.
 *
 * <p>{@code answer} is the only field the two do not share, and it is {@code null} for a search: keeping one
 * result type is what guarantees the {@code RetrievalNode} list, the degradation markers and the resolved
 * version are reported identically by both endpoints, which the requirement asks for explicitly (the search
 * {@code nodes} and the chat {@code references} are the same schema).
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = "answer")
public class KnowledgeCallResult {

    /** Ordered result list; the {@code nodes} of a search and the {@code references} of a chat. */
    private final List<RetrievalNodeView> nodes;

    /** Generated answer, {@code null} for a search. */
    private final String answer;

    /** Degradation markers, empty when the full pipeline ran. */
    private final List<String> degraded;

    /** Effective pipeline parameters. */
    private final AppliedInfo applied;

    /** Application version that served the call. */
    private final String appVersionId;

    /** Version literal that served the call. */
    private final String appVersion;

    /** Stage the serving version is in. */
    private final TargetStage targetStage;
}
