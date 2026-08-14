package io.kbrag.domain.port;

import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SPI of one external data source type, introduced by M14 and extended by M23.
 *
 * <p>Implementations declare themselves as beans and are collected by the router keyed on
 * {@link #type()}, the same plug-in shape as the text splitters: adding a Confluence or IM
 * connector means adding one bean, not touching the sync engine.
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
     * Validates connector-specific field meaning without making a remote call.
     *
     * <p>The generic API can enforce field lengths and presence, but only the selected connector
     * knows whether endpoint/bucket/accessKey mean a valid site/space/email combination. The
     * application invokes this before persisting a registration or update, so configuration errors
     * fail on the request instead of appearing later as an asynchronous sync failure.
     *
     * @param config connection details
     */
    default void validateConfig(ExtSourceConfig config) {
        // Existing connectors whose generic DTO constraints are sufficient need no extra policy.
    }

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
     * @param objectKey stable key of the remote item to fetch
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
     * @param key          stable item key inside the source
     * @param displayName  operator-facing name used for the first document file name, {@code null}
     *                     when the key already carries a useful name
     * @param etag         change marker of the object body, {@code null} when the store has none
     * @param size         item size in bytes, {@code -1} when the remote API does not expose it
     * @param lastModified last modification time, {@code null} when the store has none
     */
    record RemoteObject(String key, String displayName, String etag, long size, LocalDateTime lastModified) {
    }
}
