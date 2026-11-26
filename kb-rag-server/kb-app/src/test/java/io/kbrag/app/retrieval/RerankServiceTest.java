package io.kbrag.app.retrieval;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.RerankProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankServiceTest {

    @Test
    void shouldLiftTheCapWhenParentChildIsOn() {
        KbProperties properties = new KbProperties();
        properties.getRerank().setCandidateLimit(50);
        properties.getRetrieval().setRerankCandidateTopNMultiplier(5);
        properties.getRetrieval().setRerankCandidateHardCap(100);
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.isConfigured()).thenReturn(true);
        RerankService service = new RerankService(provider, properties, Executors.newSingleThreadExecutor());

        assertEquals(50, service.effectiveCandidateLimit(5, false));
        assertEquals(50, service.effectiveCandidateLimit(8, true));
        assertEquals(60, service.effectiveCandidateLimit(12, true));
        assertEquals(100, service.effectiveCandidateLimit(30, true));
    }
}