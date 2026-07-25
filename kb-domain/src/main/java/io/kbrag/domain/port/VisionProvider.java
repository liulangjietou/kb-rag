package io.kbrag.domain.port;

import io.kbrag.domain.model.HealthStatus;

/**
 * Outbound port of the vision capability that turns an image into its textual proxy.
 *
 * <p>M1 only defines the contract and ships a disabled placeholder implementation; image
 * understanding lands in M2.
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
     * Describes an image so it can be embedded as text.
     *
     * @param imageObjectKey object storage key of the image
     * @return textual proxy of the image
     */
    String describeImage(String imageObjectKey);

    /**
     * Probes provider connectivity.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
