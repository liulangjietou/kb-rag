package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

import java.util.List;

/**
 * Outbound port of the rerank capability.
 *
 * <p>M1 only defines the contract and ships a disabled placeholder implementation; the retrieval
 * pipeline wires it in M2 together with the score threshold.
 *
 * @author owlzhangfq@gmail.com
 */
public interface RerankProvider {

    /**
     * Provider implementation name.
     *
     * @return provider name
     */
    String providerName();

    /**
     * Model identifier this instance was configured with.
     *
     * @return model name
     */
    String model();

    /**
     * Tells whether the provider holds a usable credential.
     *
     * @return {@code true} when rerank calls can be issued
     */
    boolean isConfigured();

    /**
     * Scores candidate documents against a query.
     *
     * @param query      user query
     * @param documents  candidate texts
     * @return relevance score per candidate, aligned with the input order
     */
    List<Double> rerank(String query, List<String> documents);

    /**
     * Probes provider connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
