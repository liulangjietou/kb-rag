package io.kbrag.infrastructure.config;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.infrastructure.graph.DisabledGraphStore;
import io.kbrag.infrastructure.graph.Neo4jGraphStore;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single decision point of the graph capability, requirement section 4.9.
 *
 * <p>Mirrors {@link ModelProviderConfig}: a blank {@code NEO4J_URI} yields the store that answers "no
 * graph here" and does nothing, so the extraction pipeline, the retrieval route and the cascade cleanup
 * all keep one shape and the service starts with or without Neo4j. Nothing else in the codebase reads
 * the URI.
 *
 * <p>The driver bean itself is created only when the URI is set, which is also what keeps the health
 * endpoint from probing a dependency the deployment does not run - the same rule Qdrant follows.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class GraphStoreConfig {

    /**
     * Creates the Bolt driver, only in a deployment that runs a graph.
     *
     * @param properties bound configuration
     * @return Bolt driver
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${kb.graph.uri:}'.isBlank()")
    public Driver neo4jDriver(KbProperties properties) {
        KbProperties.Graph config = properties.getGraph();
        Driver driver = GraphDatabase.driver(config.getUri(),
                AuthTokens.basic(config.getUser(), config.getPassword()));
        log.info("neo4j driver initialized, uri={}, maxHops={}, entityMatchLimit={}",
                config.getUri(), config.getMaxHops(), config.getEntityMatchLimit());
        return driver;
    }

    /**
     * Selects the graph store implementation.
     *
     * <p>The driver is injected as an optional collaborator rather than through two conditional bean
     * definitions: one method, one place where "configured or not" is decided, and a caller that could
     * never receive a half wired store.
     *
     * @param driver     Bolt driver, {@code null} when no URI is configured
     * @param properties bound configuration
     * @return Neo4j backed store, or the disabled placeholder
     */
    @Bean
    public GraphStore graphStore(ObjectProvider<Driver> driver, KbProperties properties) {
        Driver resolved = driver.getIfAvailable();
        if (resolved == null) {
            log.info("neo4j not configured, graph route and graph extraction disabled");
            return new DisabledGraphStore();
        }
        return new Neo4jGraphStore(resolved, properties);
    }
}
