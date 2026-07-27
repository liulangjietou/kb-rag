package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

/**
 * Outbound port of the vision capability that turns an image into its textual proxy.
 *
 * <p>The port takes the bytes rather than an object storage key: the provider is a model adapter and has
 * no business knowing where the image is stored, and passing the binary keeps the same implementation
 * usable for an image that was never persisted, such as the preview of a re-parse.
 *
 * @author owlzhangfq@gmail.com
 */
public interface VisionProvider {

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
     * @return {@code true} when vision calls can be issued
     */
    boolean isConfigured();

    /**
     * Describes an image and transcribes the text it contains.
     *
     * @param content   raw image bytes
     * @param mediaType MIME type of the bytes, for example {@code image/png}
     * @return textual proxy of the image
     */
    String describeImage(byte[] content, String mediaType);

    /**
     * Probes provider connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
