package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.model.KbRetrievalConfig;
import org.springframework.stereotype.Component;

/**
 * The single gate of the rule "a knowledge base with the graph route on fuses with reciprocal ranks",
 * requirement section 4.4.
 *
 * <p><b>Why the two are exclusive.</b> Weighted fusion sums per route scores after a min-max
 * normalisation and its weights are defined to add up to one over <em>two</em> routes. The graph
 * relevance is a hop discounted match score - a third quantity, on a third scale, with no weight defined
 * for it. Adding it to a weighted sum would produce a number that looks like a score and ranks by
 * accident, which is worse than refusing the combination.
 *
 * <p><b>Why the check lives at the write side only.</b> Validating at search time would mean a knowledge
 * base could be saved in a state the pipeline then has to repair on every single call, and the repair -
 * silently switching the mode - would contradict what the console displays. Rejecting the write keeps
 * exactly one definition of a valid configuration, so nothing downstream re-checks it: the retrieval
 * service reads {@code fusion_mode} and trusts it.
 *
 * <p>Both writers of a retrieval configuration go through here: the knowledge base configuration
 * endpoint, and the application version snapshot, which completes its unset fields from the same base.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class GraphFusionPolicy {

    private static final String MESSAGE =
            "开启图路的知识库库内融合强制为 RRF，不能同时使用 weighted 加权融合";

    /**
     * Rejects a retrieval configuration that switches the graph route on while asking for weighted
     * fusion.
     *
     * @param config retrieval configuration being written, {@code null} accepted as "nothing changed"
     */
    public void requireCompatible(KbRetrievalConfig config) {
        if (config == null || !config.graphEnabled()) {
            return;
        }
        if (FusionMode.from(config.getFusionMode()) == FusionMode.WEIGHTED) {
            throw BizException.invalidParam(MESSAGE);
        }
    }
}
