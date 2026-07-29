package io.kbrag.app.system;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ModelStatus;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.port.VisionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the multimodal capability signal the console needs to grey out the page index switch: the
 * status reports the multimodal provider independently of the other four, so a deployment that holds
 * every other key but no multimodal one still sees exactly that one control disabled (M14 contract 6.2).
 *
 * @author owlzhangfq@gmail.com
 */
class ModelStatusServiceTest {

    private EmbeddingProvider embeddingProvider;
    private RerankProvider rerankProvider;
    private ChatProvider chatProvider;
    private VisionProvider visionProvider;
    private MultimodalEmbeddingProvider multimodalEmbeddingProvider;
    private ModelStatusService service;

    @BeforeEach
    void setUp() {
        embeddingProvider = mock(EmbeddingProvider.class);
        rerankProvider = mock(RerankProvider.class);
        chatProvider = mock(ChatProvider.class);
        visionProvider = mock(VisionProvider.class);
        multimodalEmbeddingProvider = mock(MultimodalEmbeddingProvider.class);
        stubIdleProviders();
        lenient().when(embeddingProvider.dimension()).thenReturn(0);
        service = new ModelStatusService(embeddingProvider, rerankProvider, chatProvider,
                visionProvider, multimodalEmbeddingProvider, new KbProperties());
    }

    @Test
    void shouldReportTheMultimodalCapabilityAsUsableWhenTheProviderHoldsACredential() {
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(true);
        when(multimodalEmbeddingProvider.providerName()).thenReturn("dashscope");
        when(multimodalEmbeddingProvider.model()).thenReturn("multimodal-embedding-v1");

        ModelStatus status = service.current();

        assertTrue(status.isMultimodalConfigured());
        assertEquals("dashscope", status.getMultimodalProvider());
        assertEquals("multimodal-embedding-v1", status.getMultimodalModel());
    }

    @Test
    void shouldReportTheMultimodalCapabilityAsDisabledWhenTheProviderHasNoCredential() {
        when(multimodalEmbeddingProvider.isConfigured()).thenReturn(false);
        lenient().when(multimodalEmbeddingProvider.providerName()).thenReturn("none");
        lenient().when(multimodalEmbeddingProvider.model()).thenReturn("none");

        ModelStatus status = service.current();

        // A missing multimodal key must disable only this switch, never the other four capabilities.
        assertFalse(status.isMultimodalConfigured());
    }

    private void stubIdleProviders() {
        lenient().when(embeddingProvider.providerName()).thenReturn("none");
        lenient().when(embeddingProvider.model()).thenReturn("none");
        lenient().when(rerankProvider.providerName()).thenReturn("none");
        lenient().when(rerankProvider.model()).thenReturn("none");
        lenient().when(chatProvider.providerName()).thenReturn("none");
        lenient().when(chatProvider.model()).thenReturn("none");
        lenient().when(visionProvider.providerName()).thenReturn("none");
        lenient().when(visionProvider.model()).thenReturn("none");
    }
}
