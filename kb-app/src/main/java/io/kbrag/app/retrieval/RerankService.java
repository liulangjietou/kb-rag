package io.kbrag.app.retrieval;

import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.port.RerankProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scores the fused candidates against the query with a cross encoder.
 *
 * <p>Why the stage exists. Recall and fusion rank documents without ever comparing the query and the
 * document in one pass: the vector route compares two independently produced embeddings, BM25 counts
 * term overlap, and fusion only merges positions. A cross encoder reads the pair together, which is
 * why its score is the only one comparable across queries and therefore the only one an absolute
 * threshold can act on.
 *
 * <p>The candidate list is capped because the cost of the stage is linear in its length while the
 * benefit is not: a document that fusion placed outside the top candidates is unlikely to be the best
 * answer, and paying for it would push the stage past the latency budget for every search.
 *
 * <p><b>The stage never blocks a search.</b> The provider call runs on a separate thread with a hard
 * timeout; on timeout or failure the coarse fusion order stands and the caller is told which of the
 * two happened, since a timeout is a capacity problem and a failure is a configuration problem.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class RerankService {

    private final RerankProvider rerankProvider;
    private final KbProperties.Rerank config;
    private final Executor executor;

    public RerankService(RerankProvider rerankProvider,
                         KbProperties properties,
                         @Qualifier(AsyncConfig.RETRIEVAL_EXECUTOR) Executor executor) {
        this.rerankProvider = rerankProvider;
        this.config = properties.getRerank();
        this.executor = executor;
    }

    /**
     * Tells whether the stage can run at all.
     *
     * @return {@code true} when a rerank model is configured
     */
    public boolean isAvailable() {
        return rerankProvider.isConfigured();
    }

    /**
     * Maximum number of candidates a single call accepts.
     *
     * @return candidate cap
     */
    public int candidateLimit() {
        return config.getCandidateLimit();
    }

    /**
     * Runs the stage.
     *
     * @param query     query the candidates were recalled with
     * @param documents candidate texts in fusion order, at most {@link #candidateLimit()} entries
     * @param enabled   {@code true} when the caller asked for a rerank
     * @return one score per candidate, or the marker explaining the fallback
     */
    public RerankOutcome rerank(String query, List<String> documents, boolean enabled) {
        if (!enabled || CollectionUtils.isEmpty(documents)) {
            // Nothing recalled means nothing to rerank, and reporting a degradation for a stage that had
            // no work would blame the model for an empty result set it never saw.
            return RerankOutcome.skipped();
        }
        if (!rerankProvider.isConfigured()) {
            log.info("rerank requested without a rerank model, keeping the fusion order");
            return RerankOutcome.degraded(DegradedReason.RERANK_UNAVAILABLE.code());
        }

        // The submission is guarded separately from the wait: the retrieval pool is bounded and queueless
        // by design, so under load it rejects synchronously, and an unguarded rejection here would fail
        // the whole search instead of dropping back to the fusion order.
        CompletableFuture<List<Double>> future;
        try {
            future = CompletableFuture.supplyAsync(() -> rerankProvider.rerank(query, documents), executor);
        } catch (Exception e) {
            log.error("rerank could not be scheduled, errorCode={}, candidates={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, documents.size(), e);
            return RerankOutcome.degraded(DegradedReason.RERANK_ERROR.code());
        }

        try {
            List<Double> scores = future.get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (scores.size() != documents.size()) {
                log.error("rerank returned {} scores for {} candidates, errorCode={}",
                        scores.size(), documents.size(), ErrorCode.UPSTREAM_MODEL_ERROR);
                return RerankOutcome.degraded(DegradedReason.RERANK_ERROR.code());
            }
            log.info("rerank applied, candidates={}, model={}", documents.size(), rerankProvider.model());
            return RerankOutcome.applied(scores);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.info("rerank timed out after {}ms, keeping the fusion order", config.getTimeoutMs());
            return RerankOutcome.degraded(DegradedReason.RERANK_TIMEOUT.code());
        } catch (Exception e) {
            log.error("rerank failed, errorCode={}, candidates={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, documents.size(), e);
            return RerankOutcome.degraded(DegradedReason.RERANK_ERROR.code());
        }
    }
}
