package io.kbrag.infrastructure.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Builds the shared Elasticsearch client.
 *
 * <p>Elasticsearch is a mandatory dependency in both deployment modes: it always serves the BM25
 * route and, in lite mode, the vector route as well.
 *
 * <p>The transport is exposed as its own bean because it, not the typed client, owns the socket pool
 * and is the object that has to be closed on shutdown.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class ElasticsearchClientConfig {

    private static final int DEFAULT_HTTP_PORT = 9200;

    /**
     * Creates the low level transport from the configured URI.
     *
     * @param properties bound configuration
     * @return transport, closed when the context shuts down
     */
    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(KbProperties properties) {
        KbProperties.Elasticsearch config = properties.getEs();
        URI uri = URI.create(config.getUri());
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_HTTP_PORT;
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), port, uri.getScheme()));
        if (!config.getUsername().isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
            builder.setHttpClientConfigCallback(httpClient -> httpClient.setDefaultCredentialsProvider(credentials));
        }
        log.info("elasticsearch transport initialized, uri={}", config.getUri());
        return new RestClientTransport(builder.build(), new JacksonJsonpMapper(JsonUtil.mapper()));
    }

    /**
     * Creates the typed client on top of the transport.
     *
     * @param transport shared transport
     * @return typed client
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
