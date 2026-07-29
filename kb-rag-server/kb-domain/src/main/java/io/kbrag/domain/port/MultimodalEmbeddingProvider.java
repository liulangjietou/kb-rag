package io.kbrag.domain.port;

import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ImageInput;

import java.util.List;

/**
 * Outbound port of the multimodal embedding capability, the M14 contract section 6.1.
 *
 * <p>Text and images are embedded into one shared space, which is what lets a text query recall an
 * image chunk and, in F6, an image query recall a text one. The dimension is frozen into the
 * multimodal index at creation time exactly like the text embedding port, so a model whose dimension
 * differs forces a rebuild instead of being adapted in place.
 *
 * <p>A blank credential yields the unconfigured implementation whose {@link #isConfigured()} returns
 * {@code false}: the index pipeline then skips the multimodal vectors and retrieval skips the third
 * route, which keeps the whole capability free of scattered null checks - a caller asks the flag once
 * and either runs the route or does not.
 *
 * @author owlzhangfq@gmail.com
 */
public interface MultimodalEmbeddingProvider {

    /**
     * Provider implementation name recorded in the multimodal index registry.
     *
     * @return provider name, for example {@code dashscope} or {@code none}
     */
    String providerName();

    /**
     * Model identifier this instance was configured with.
     *
     * @return model name
     */
    String model();

    /**
     * Vector dimension produced by {@link #model()}.
     *
     * @return dimension, 0 when the provider is not configured
     */
    int dimension();

    /**
     * Tells whether the provider holds a usable credential.
     *
     * @return {@code true} when multimodal calls can be issued
     */
    boolean isConfigured();

    /**
     * Maximum number of items a single provider request accepts.
     *
     * @return batch size, callers must not exceed it
     */
    int maxBatchSize();

    /**
     * Embeds a batch of texts into the multimodal space, preserving the input order.
     *
     * @param texts input texts, size must not exceed {@link #maxBatchSize()}
     * @return one vector per input text
     * @throws ProviderException classified provider failure
     */
    List<float[]> embedTexts(List<String> texts);

    /**
     * Embeds a batch of images into the multimodal space, preserving the input order.
     *
     * @param images input images, size must not exceed {@link #maxBatchSize()}
     * @return one vector per input image
     * @throws ProviderException classified provider failure
     */
    List<float[]> embedImages(List<ImageInput> images);

    /**
     * Probes provider connectivity without consuming a meaningful quota.
     *
     * @return probe outcome
     */
    HealthStatus healthCheck();
}
