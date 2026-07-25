package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.enums.VectorEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the three segment index naming rules, including the deliberate asymmetry that keeps the full
 * mode full text index out of the embedding version scheme.
 */
class IndexNamingTest {

    private static final String KB_ID = "kb_7f3a91c2e5d84b60";
    private static final String KB_SUFFIX = "7f3a91c2e5d84b60";

    private final IndexNaming naming = new IndexNaming();

    @Test
    void shouldAbbreviateEmbeddingModelNames() {
        assertEquals("tev4", naming.embeddingSegment("text-embedding-v4"));
        assertEquals("bm3", naming.embeddingSegment("bge-m3"));
        assertEquals(KbConstants.EMBEDDING_SEGMENT_NONE, naming.embeddingSegment(""));
        assertEquals(KbConstants.EMBEDDING_SEGMENT_NONE, naming.embeddingSegment(null));
    }

    @Test
    void shouldBuildVectorPhysicalName() {
        assertEquals("kb_" + KB_SUFFIX + "_tev4_v1", naming.vectorPhysicalName(KB_ID, "tev4"));
    }

    @Test
    void shouldGiveTheFullModeFulltextIndexTheBm25Segment() {
        assertEquals("kb_" + KB_SUFFIX + "_bm25_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.MILVUS, "tev4"));
    }

    @Test
    void shouldKeepTheEmbeddingSegmentOnTheLiteFulltextIndex() {
        assertEquals("kb_" + KB_SUFFIX + "_tev4_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.ES, "tev4"));
    }

    @Test
    void shouldUseTheZeroKeySegmentWhenNoModelIsConfigured() {
        assertEquals("kb_" + KB_SUFFIX + "_none_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.ES, KbConstants.EMBEDDING_SEGMENT_NONE));
    }

    @Test
    void shouldBuildAliasPerEngine() {
        assertEquals("kb_" + KB_SUFFIX + "_es", naming.alias(KB_ID, VectorEngine.ES));
        assertEquals("kb_" + KB_SUFFIX + "_milvus", naming.alias(KB_ID, VectorEngine.MILVUS));
    }
}
