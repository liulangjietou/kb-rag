package io.kbrag.domain.service;

import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingSemanticTextSplitterTest {

    @Test
    void shouldCutWhenEmbeddingSimilarityDrops() {
        EmbeddingProvider embedding = mock(EmbeddingProvider.class);
        when(embedding.isConfigured()).thenReturn(true);
        when(embedding.maxBatchSize()).thenReturn(32);
        when(embedding.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> switch (text) {
                case "Topic A." -> new float[]{1f, 0f};
                case "Still A." -> new float[]{0.95f, 0.05f};
                case "Topic B." -> new float[]{0f, 1f};
                default -> new float[]{0.1f, 0.1f};
            }).toList();
        });
        FixedLengthTextSplitter fixed = new FixedLengthTextSplitter(new SimpleTokenEstimator());
        EmbeddingSemanticTextSplitter splitter = new EmbeddingSemanticTextSplitter(embedding, fixed);
        SplitParams params = SplitParams.of(8, 0, null, null, false, 0, 0.5d, false, 0);
        var chunks = splitter.split("## Part one\nTopic A. Still A.\n## Part two\nTopic B.", params);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("Part one")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("Part two")));
    }
}