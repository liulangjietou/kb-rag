package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

import java.io.InputStream;
import java.time.Duration;

/**
 * Outbound port of the private object storage holding original files and parse artifacts.
 */
public interface ObjectStorage {

    /**
     * Stores an object, creating the bucket on first use.
     *
     * @param objectKey   target key
     * @param content     payload stream, closed by the implementation
     * @param size        payload size in bytes
     * @param contentType MIME type
     */
    void put(String objectKey, InputStream content, long size, String contentType);

    /**
     * Reads an object.
     *
     * @param objectKey key to read
     * @return payload stream, the caller closes it
     */
    InputStream get(String objectKey);

    /**
     * Issues a time limited pre signed download URL; the bucket itself stays private.
     *
     * @param objectKey key to expose
     * @param ttl       validity window
     * @return pre signed URL
     */
    String presignedUrl(String objectKey, Duration ttl);

    /**
     * Probes storage connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
