package io.kbrag.domain.port;

import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SPI of one external data source type, the M14 contract section 2.1.
 *
 * <p>Implementations declare themselves as beans and are collected by the router keyed on
 * {@link #type()}, the same plug-in shape as the text splitters: adding a Confluence or IM
 * connector later means adding one bean, not touching the sync engine.
 *
 * @author owlzhangfq@gmail.com
 */
public interface ExternalConnector {

    /**
     * Routing key stored in {@code t_kb_ext_source.source_type}.
     *
     * @return connector type literal, lower case
     */
    String type();

    /**
     * Lists the objects a sync pass should consider.
     *
     * <p>May return up to one object beyond {@link ExtSourceConfig#maxObjects()} so the caller can
     * distinguish a complete listing from a truncated one without a second remote call.
     *
     * @param config connection details
     * @return listed objects, never {@code null}
     */
    List<RemoteObject> listObjects(ExtSourceConfig config);

    /**
     * Fetches one object body.
     *
     * @param config    connection details
     * @param objectKey key of the object to fetch
     * @return object bytes
     */
    byte[] fetchObject(ExtSourceConfig config, String objectKey);

    /**
     * Probes connectivity and credentials without touching any object.
     *
     * @param config connection details
     * @return probe outcome, never throws
     */
    HealthStatus testConnection(ExtSourceConfig config);

    /**
     * One object surfaced by a listing.
     *
     * @param key          object key inside the bucket
     * @param etag         change marker of the object body, {@code null} when the store has none
     * @param size         object size in bytes
     * @param lastModified last modification time, {@code null} when the store has none
     */
    record RemoteObject(String key, String etag, long size, LocalDateTime lastModified) {
    }
}
